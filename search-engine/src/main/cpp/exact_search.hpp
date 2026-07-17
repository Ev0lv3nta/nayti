#pragma once

#include "segment_format.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace nayti::search {

inline constexpr std::size_t kMaxTopK = 512;

using EligibilityFilter = bool (*)(std::uint64_t asset_id, void *context);

struct SearchHit {
  std::uint64_t record_id;
  std::uint64_t asset_id;
  std::uint32_t ordinal;
  std::int32_t score;

  bool operator==(const SearchHit &) const = default;
};

enum class ScanError {
  kNone,
  kInvalidK,
  kInvalidQueryDimension,
  kInvalidEmbeddingSpace,
  kIncompatibleSegment,
};

std::int32_t dot_product_scalar(std::span<const std::int8_t> left,
                                std::span<const std::int8_t> right);
std::int32_t dot_product(std::span<const std::int8_t> left,
                         std::span<const std::int8_t> right);

class ExactTopKScanner {
public:
  // The query storage must remain alive until the scanner is destroyed.
  ExactTopKScanner(std::span<const std::int8_t> query, std::size_t k,
                   Channel channel,
                   std::span<const std::byte, 32> embedding_space_hash);

  ScanError status() const { return status_; }
  ScanError scan(const SegmentView &segment, EligibilityFilter filter = nullptr,
                 void *filter_context = nullptr);
  std::vector<SearchHit> results() const;
  std::size_t retained_hit_count() const { return heap_.size(); }

private:
  std::span<const std::int8_t> query_;
  std::size_t k_;
  Channel channel_;
  std::array<std::byte, 32> embedding_space_hash_;
  ScanError status_;
  std::vector<SearchHit> heap_;
};

} // namespace nayti::search
