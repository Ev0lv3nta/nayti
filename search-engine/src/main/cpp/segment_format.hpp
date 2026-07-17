#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <vector>

namespace nayti::search {

inline constexpr std::size_t kSegmentHeaderSize = 128;
inline constexpr std::size_t kRecordEntrySize = 24;
inline constexpr std::uint16_t kSegmentFormatVersion = 1;
inline constexpr std::uint32_t kMaxDimension = 4096;
inline constexpr std::uint32_t kMaxRecordCount = 256;

enum class Channel : std::uint8_t {
  kVisual = 1,
  kOcrSemantic = 2,
};

enum class ParseError {
  kNone,
  kTruncated,
  kInvalidMagic,
  kUnsupportedVersion,
  kInvalidHeader,
  kInvalidChannel,
  kInvalidEncoding,
  kInvalidShape,
  kInvalidLayout,
  kInvalidIdentity,
  kNonCanonicalPadding,
  kInvalidRecord,
  kDuplicateRecord,
};

struct RecordMetadata {
  std::uint64_t record_id;
  std::uint64_t asset_id;
  std::uint32_t ordinal;
};

struct ParseResult;

class SegmentView {
public:
  Channel channel() const { return channel_; }
  std::uint32_t dimension() const { return dimension_; }
  std::span<const RecordMetadata> records() const { return records_; }
  std::span<const std::byte, 32> embedding_space_hash() const {
    return embedding_space_hash_;
  }
  std::span<const std::byte, 16> segment_id() const { return segment_id_; }
  std::span<const std::int8_t> vector(std::size_t index) const;

private:
  friend struct ParseResult;
  friend ParseResult parse_segment(std::span<const std::byte> bytes);

  Channel channel_ = Channel::kVisual;
  std::uint32_t dimension_ = 0;
  std::vector<RecordMetadata> records_;
  std::array<std::byte, 32> embedding_space_hash_{};
  std::array<std::byte, 16> segment_id_{};
  std::span<const std::int8_t> payload_;
};

struct ParseResult {
  ParseError error = ParseError::kNone;
  std::optional<SegmentView> segment;

  explicit operator bool() const { return segment.has_value(); }
};

// The source bytes must outlive the returned view; MappedSegment owns this
// lifetime in production.
ParseResult parse_segment(std::span<const std::byte> bytes);

} // namespace nayti::search
