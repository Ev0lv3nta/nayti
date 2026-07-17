#include "exact_search.hpp"
#include "segment_format.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <random>
#include <span>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;
using Microseconds = std::chrono::duration<double, std::micro>;

template <typename T>
void write_little_endian(std::vector<std::byte> &bytes, std::size_t offset,
                         T value) {
  for (std::size_t index = 0; index < sizeof(T); ++index) {
    bytes[offset + index] =
        static_cast<std::byte>((value >> (index * 8U)) & 0xffU);
  }
}

std::size_t align_up(std::size_t value, std::size_t alignment) {
  return (value + alignment - 1U) / alignment * alignment;
}

struct OwnedSegment {
  std::vector<std::byte> bytes;
  nayti::search::SegmentView view;
};

std::vector<OwnedSegment> make_corpus(std::size_t record_count,
                                      std::size_t dimension,
                                      std::mt19937 *generator) {
  std::uniform_int_distribution<int> values(-127, 127);
  std::vector<OwnedSegment> corpus;
  corpus.reserve((record_count + nayti::search::kMaxRecordCount - 1U) /
                 nayti::search::kMaxRecordCount);
  std::size_t first_record = 1;
  while (first_record <= record_count) {
    const std::size_t count = std::min<std::size_t>(
        nayti::search::kMaxRecordCount, record_count - first_record + 1U);
    const std::size_t table_end = nayti::search::kSegmentHeaderSize +
                                  count * nayti::search::kRecordEntrySize;
    const std::size_t payload_offset = align_up(table_end, 64);
    std::vector<std::byte> bytes(payload_offset + count * dimension,
                                 std::byte{0});
    constexpr std::array<char, 8> magic = {'N', 'A', 'Y', 'T',
                                           'I', 'V', 'E', 'C'};
    for (std::size_t index = 0; index < magic.size(); ++index) {
      bytes[index] = static_cast<std::byte>(magic[index]);
    }
    write_little_endian<std::uint16_t>(bytes, 8, 1);
    write_little_endian<std::uint16_t>(bytes, 10, 128);
    bytes[12] = std::byte{1};
    bytes[13] = std::byte{1};
    bytes[14] = std::byte{1};
    write_little_endian<std::uint32_t>(bytes, 16,
                                       static_cast<std::uint32_t>(dimension));
    write_little_endian<std::uint32_t>(bytes, 20,
                                       static_cast<std::uint32_t>(count));
    write_little_endian<std::uint16_t>(bytes, 24, 24);
    write_little_endian<std::uint64_t>(bytes, 28, 128);
    write_little_endian<std::uint64_t>(bytes, 36, payload_offset);
    write_little_endian<std::uint64_t>(bytes, 44, count * dimension);
    write_little_endian<std::uint64_t>(bytes, 52, bytes.size());
    bytes[60] = std::byte{0x5a};
    bytes[92] = std::byte{0xa5};

    for (std::size_t index = 0; index < count; ++index) {
      const auto record_id = static_cast<std::uint64_t>(first_record + index);
      const std::size_t record_offset = 128 + index * 24;
      write_little_endian<std::uint64_t>(bytes, record_offset, record_id);
      write_little_endian<std::uint64_t>(bytes, record_offset + 8, record_id);
    }
    for (std::size_t index = 0; index < count * dimension; ++index) {
      bytes[payload_offset + index] =
          static_cast<std::byte>(static_cast<std::int8_t>(values(*generator)));
    }

    auto parsed = nayti::search::parse_segment(bytes);
    if (!parsed) {
      std::cerr << "Generated benchmark segment did not parse.\n";
      std::exit(EXIT_FAILURE);
    }
    corpus.push_back(
        {.bytes = std::move(bytes), .view = std::move(*parsed.segment)});
    first_record += count;
  }
  return corpus;
}

void benchmark(std::size_t record_count, std::size_t dimension,
               const char *label) {
  std::mt19937 generator(0x4e415954U + static_cast<std::uint32_t>(dimension));
  auto corpus = make_corpus(record_count, dimension, &generator);
  std::uniform_int_distribution<int> values(-127, 127);
  std::vector<std::int8_t> query(dimension);
  for (auto &value : query) {
    value = static_cast<std::int8_t>(values(generator));
  }

  constexpr std::size_t k = 50;
  constexpr std::size_t iterations = 9;
  std::array<double, iterations> timings{};
  std::int64_t checksum = 0;
  for (std::size_t iteration = 0; iteration <= iterations; ++iteration) {
    nayti::search::ExactTopKScanner scanner(
        query, k, corpus.front().view.channel(),
        corpus.front().view.embedding_space_hash());
    const auto started = Clock::now();
    for (const auto &segment : corpus) {
      if (scanner.scan(segment.view) != nayti::search::ScanError::kNone) {
        std::cerr << "Benchmark scan failed.\n";
        std::exit(EXIT_FAILURE);
      }
    }
    const auto elapsed = Microseconds(Clock::now() - started).count();
    const auto results = scanner.results();
    checksum += results.front().score;
    if (scanner.retained_hit_count() != k) {
      std::cerr << "Benchmark retained an unexpected number of hits.\n";
      std::exit(EXIT_FAILURE);
    }
    if (iteration != 0) {
      timings[iteration - 1] = elapsed;
    }
  }
  std::sort(timings.begin(), timings.end());
  std::cout << label << ": records=" << record_count
            << ", dimension=" << dimension << ", segments=" << corpus.size()
            << ", top_k=" << k << ", retained_scores=" << k
            << ", median_us=" << timings[timings.size() / 2]
            << ", checksum=" << checksum << '\n';
}

} // namespace

int main() {
  benchmark(13'000, 768, "visual");
  benchmark(50'000, 384, "ocr_semantic");
  return EXIT_SUCCESS;
}
