#include "mapped_segment.hpp"

#include "sha256.hpp"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstddef>
#include <limits>
#include <span>
#include <utility>

namespace nayti::search {

MappedSegment::~MappedSegment() { reset(); }

MappedSegment::MappedSegment(MappedSegment &&other) noexcept
    : file_descriptor_(std::exchange(other.file_descriptor_, -1)),
      mapping_(std::exchange(other.mapping_, nullptr)),
      byte_length_(std::exchange(other.byte_length_, 0)),
      segment_(std::move(other.segment_)) {
  other.segment_.reset();
}

MappedSegment &MappedSegment::operator=(MappedSegment &&other) noexcept {
  if (this != &other) {
    reset();
    file_descriptor_ = std::exchange(other.file_descriptor_, -1);
    mapping_ = std::exchange(other.mapping_, nullptr);
    byte_length_ = std::exchange(other.byte_length_, 0);
    segment_ = std::move(other.segment_);
    other.segment_.reset();
  }
  return *this;
}

void MappedSegment::reset() {
  segment_.reset();
  if (mapping_ != nullptr) {
    ::munmap(mapping_, byte_length_);
    mapping_ = nullptr;
  }
  byte_length_ = 0;
  if (file_descriptor_ >= 0) {
    ::close(file_descriptor_);
    file_descriptor_ = -1;
  }
}

MappedSegmentResult
map_segment(const std::string &path, std::uint64_t expected_length,
            std::span<const std::byte, 32> expected_sha256) {
  MappedSegment mapped;
  mapped.file_descriptor_ =
      ::open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
  if (mapped.file_descriptor_ < 0) {
    return {.error = MapError::kOpenFailed,
            .parse_error = ParseError::kNone,
            .mapped = std::nullopt};
  }

  struct stat file_status {};
  if (::fstat(mapped.file_descriptor_, &file_status) != 0 ||
      !S_ISREG(file_status.st_mode)) {
    return {.error = MapError::kNotRegularFile,
            .parse_error = ParseError::kNone,
            .mapped = std::nullopt};
  }
  if (file_status.st_size <= 0 ||
      static_cast<std::uintmax_t>(file_status.st_size) >
          std::numeric_limits<std::size_t>::max()) {
    return {.error = MapError::kInvalidFileSize,
            .parse_error = ParseError::kNone,
            .mapped = std::nullopt};
  }
  if (static_cast<std::uint64_t>(file_status.st_size) != expected_length) {
    return {.error = MapError::kLengthMismatch,
            .parse_error = ParseError::kNone,
            .mapped = std::nullopt};
  }

  mapped.byte_length_ = static_cast<std::size_t>(file_status.st_size);
  mapped.mapping_ = ::mmap(nullptr, mapped.byte_length_, PROT_READ, MAP_PRIVATE,
                           mapped.file_descriptor_, 0);
  if (mapped.mapping_ == MAP_FAILED) {
    mapped.mapping_ = nullptr;
    return {.error = MapError::kMapFailed,
            .parse_error = ParseError::kNone,
            .mapped = std::nullopt};
  }

  const auto *data = static_cast<const std::byte *>(mapped.mapping_);
  const auto mapped_bytes =
      std::span<const std::byte>(data, mapped.byte_length_);
  const auto actual_sha256 = sha256(mapped_bytes);
  std::byte difference{0};
  for (std::size_t index = 0; index < actual_sha256.size(); ++index) {
    difference |= actual_sha256[index] ^ expected_sha256[index];
  }
  if (difference != std::byte{0}) {
    return {.error = MapError::kHashMismatch,
            .parse_error = ParseError::kNone,
            .mapped = std::nullopt};
  }
  auto parsed = parse_segment(mapped_bytes);
  if (!parsed) {
    return {.error = MapError::kInvalidSegment,
            .parse_error = parsed.error,
            .mapped = std::nullopt};
  }
  mapped.segment_ = std::move(parsed.segment);
  return {.error = MapError::kNone,
          .parse_error = ParseError::kNone,
          .mapped = std::move(mapped)};
}

} // namespace nayti::search
