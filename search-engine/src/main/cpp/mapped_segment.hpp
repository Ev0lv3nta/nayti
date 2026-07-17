#pragma once

#include "segment_format.hpp"

#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string>

namespace nayti::search {

enum class MapError {
  kNone,
  kOpenFailed,
  kNotRegularFile,
  kInvalidFileSize,
  kLengthMismatch,
  kMapFailed,
  kHashMismatch,
  kInvalidSegment,
};

struct MappedSegmentResult;

class MappedSegment {
public:
  MappedSegment() = default;
  ~MappedSegment();
  MappedSegment(const MappedSegment &) = delete;
  MappedSegment &operator=(const MappedSegment &) = delete;
  MappedSegment(MappedSegment &&other) noexcept;
  MappedSegment &operator=(MappedSegment &&other) noexcept;

  const SegmentView &segment() const { return *segment_; }
  std::size_t byte_length() const { return byte_length_; }

private:
  friend struct MappedSegmentResult;
  friend MappedSegmentResult
  map_segment(const std::string &path, std::uint64_t expected_length,
              std::span<const std::byte, 32> expected_sha256);

  void reset();

  int file_descriptor_ = -1;
  void *mapping_ = nullptr;
  std::size_t byte_length_ = 0;
  std::optional<SegmentView> segment_;
};

struct MappedSegmentResult {
  MapError error = MapError::kNone;
  ParseError parse_error = ParseError::kNone;
  std::optional<MappedSegment> mapped;

  explicit operator bool() const { return mapped.has_value(); }
};

// Opens the path once, verifies trusted manifest evidence on that inode, and
// owns the fd/mapping. Published segment files are immutable; replacement
// happens only by publishing a new path.
MappedSegmentResult map_segment(const std::string &path,
                                std::uint64_t expected_length,
                                std::span<const std::byte, 32> expected_sha256);

} // namespace nayti::search
