#include "exact_search.hpp"

#include <algorithm>

#if defined(__aarch64__) && defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace nayti::search {
namespace {

bool is_better(const SearchHit &left, const SearchHit &right) {
  return left.score > right.score ||
         (left.score == right.score && left.record_id < right.record_id);
}

struct Better {
  bool operator()(const SearchHit &left, const SearchHit &right) const {
    return is_better(left, right);
  }
};

#if defined(__aarch64__) && defined(__ARM_NEON)
std::int32_t dot_product_neon(std::span<const std::int8_t> left,
                              std::span<const std::int8_t> right) {
  int32x4_t accumulator = vdupq_n_s32(0);
  std::size_t index = 0;
  for (; index + 16 <= left.size(); index += 16) {
    const int8x16_t left_values = vld1q_s8(left.data() + index);
    const int8x16_t right_values = vld1q_s8(right.data() + index);
    const int16x8_t low_products =
        vmull_s8(vget_low_s8(left_values), vget_low_s8(right_values));
    const int16x8_t high_products =
        vmull_s8(vget_high_s8(left_values), vget_high_s8(right_values));
    accumulator = vaddq_s32(accumulator, vpaddlq_s16(low_products));
    accumulator = vaddq_s32(accumulator, vpaddlq_s16(high_products));
  }
  std::int32_t result = vaddvq_s32(accumulator);
  for (; index < left.size(); ++index) {
    result += static_cast<std::int32_t>(left[index]) *
              static_cast<std::int32_t>(right[index]);
  }
  return result;
}
#endif

} // namespace

std::int32_t dot_product_scalar(std::span<const std::int8_t> left,
                                std::span<const std::int8_t> right) {
  if (left.size() != right.size()) {
    return 0;
  }
  std::int32_t result = 0;
  for (std::size_t index = 0; index < left.size(); ++index) {
    result += static_cast<std::int32_t>(left[index]) *
              static_cast<std::int32_t>(right[index]);
  }
  return result;
}

std::int32_t dot_product(std::span<const std::int8_t> left,
                         std::span<const std::int8_t> right) {
  if (left.size() != right.size()) {
    return 0;
  }
#if defined(__aarch64__) && defined(__ARM_NEON)
  return dot_product_neon(left, right);
#else
  return dot_product_scalar(left, right);
#endif
}

ExactTopKScanner::ExactTopKScanner(
    std::span<const std::int8_t> query, std::size_t k, Channel channel,
    std::span<const std::byte, 32> embedding_space_hash)
    : query_(query), k_(k), channel_(channel), embedding_space_hash_(),
      status_(k == 0 || k > kMaxTopK ? ScanError::kInvalidK
                                     : ScanError::kNone) {
  if (status_ == ScanError::kNone &&
      (query.empty() || query.size() > kMaxDimension)) {
    status_ = ScanError::kInvalidQueryDimension;
  }
  if (status_ == ScanError::kNone &&
      std::all_of(embedding_space_hash.begin(), embedding_space_hash.end(),
                  [](std::byte value) { return value == std::byte{0}; })) {
    status_ = ScanError::kInvalidEmbeddingSpace;
  }
  if (status_ == ScanError::kNone) {
    std::copy(embedding_space_hash.begin(), embedding_space_hash.end(),
              embedding_space_hash_.begin());
    heap_.reserve(k_);
  }
}

ScanError ExactTopKScanner::scan(const SegmentView &segment,
                                 EligibilityFilter filter,
                                 void *filter_context) {
  if (status_ != ScanError::kNone) {
    return status_;
  }
  if (segment.dimension() != query_.size() || segment.channel() != channel_ ||
      !std::equal(embedding_space_hash_.begin(), embedding_space_hash_.end(),
                  segment.embedding_space_hash().begin())) {
    return ScanError::kIncompatibleSegment;
  }

  const auto records = segment.records();
  for (std::size_t index = 0; index < records.size(); ++index) {
    const auto &record = records[index];
    if (filter != nullptr && !filter(record, filter_context)) {
      continue;
    }
    SearchHit hit{
        .record_id = record.record_id,
        .asset_id = record.asset_id,
        .ordinal = record.ordinal,
        .score = dot_product(segment.vector(index), query_),
    };
    if (heap_.size() < k_) {
      heap_.push_back(hit);
      std::push_heap(heap_.begin(), heap_.end(), Better{});
    } else if (is_better(hit, heap_.front())) {
      std::pop_heap(heap_.begin(), heap_.end(), Better{});
      heap_.back() = hit;
      std::push_heap(heap_.begin(), heap_.end(), Better{});
    }
  }
  return ScanError::kNone;
}

std::vector<SearchHit> ExactTopKScanner::results() const {
  auto sorted = heap_;
  std::sort(sorted.begin(), sorted.end(), Better{});
  return sorted;
}

} // namespace nayti::search
