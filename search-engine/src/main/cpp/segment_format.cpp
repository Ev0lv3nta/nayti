#include "segment_format.hpp"

#include <algorithm>
#include <array>
#include <limits>

namespace nayti::search {
namespace {

constexpr std::array<std::byte, 8> kMagic = {
    std::byte{'N'}, std::byte{'A'}, std::byte{'Y'}, std::byte{'T'},
    std::byte{'I'}, std::byte{'V'}, std::byte{'E'}, std::byte{'C'},
};

template <typename T>
T read_little_endian(std::span<const std::byte> bytes, std::size_t offset) {
  T value = 0;
  for (std::size_t index = 0; index < sizeof(T); ++index) {
    value |=
        static_cast<T>(std::to_integer<unsigned int>(bytes[offset + index]))
        << (index * 8U);
  }
  return value;
}

bool all_zero(std::span<const std::byte> bytes) {
  return std::all_of(bytes.begin(), bytes.end(),
                     [](std::byte value) { return value == std::byte{0}; });
}

ParseResult failure(ParseError error) {
  return {.error = error, .segment = std::nullopt};
}

bool checked_multiply(std::uint64_t left, std::uint64_t right,
                      std::uint64_t *result) {
  if (left != 0 && right > std::numeric_limits<std::uint64_t>::max() / left) {
    return false;
  }
  *result = left * right;
  return true;
}

} // namespace

std::span<const std::int8_t> SegmentView::vector(std::size_t index) const {
  if (index >= records_.size()) {
    return {};
  }
  return payload_.subspan(index * dimension_, dimension_);
}

ParseResult parse_segment(std::span<const std::byte> bytes) {
  if (bytes.size() < kSegmentHeaderSize) {
    return failure(ParseError::kTruncated);
  }
  if (!std::equal(kMagic.begin(), kMagic.end(), bytes.begin())) {
    return failure(ParseError::kInvalidMagic);
  }

  const auto version = read_little_endian<std::uint16_t>(bytes, 8);
  if (version != kSegmentFormatVersion) {
    return failure(ParseError::kUnsupportedVersion);
  }
  if (read_little_endian<std::uint16_t>(bytes, 10) != kSegmentHeaderSize ||
      read_little_endian<std::uint16_t>(bytes, 24) != kRecordEntrySize ||
      read_little_endian<std::uint16_t>(bytes, 26) != 0 ||
      !all_zero(bytes.subspan(108, 20)) ||
      std::to_integer<std::uint8_t>(bytes[15]) != 0) {
    return failure(ParseError::kInvalidHeader);
  }

  const auto channel_value = std::to_integer<std::uint8_t>(bytes[12]);
  if (channel_value != static_cast<std::uint8_t>(Channel::kVisual) &&
      channel_value != static_cast<std::uint8_t>(Channel::kOcrSemantic)) {
    return failure(ParseError::kInvalidChannel);
  }
  if (std::to_integer<std::uint8_t>(bytes[13]) != 1 ||
      std::to_integer<std::uint8_t>(bytes[14]) != 1) {
    return failure(ParseError::kInvalidEncoding);
  }

  const auto dimension = read_little_endian<std::uint32_t>(bytes, 16);
  const auto record_count = read_little_endian<std::uint32_t>(bytes, 20);
  if (dimension == 0 || dimension > kMaxDimension || record_count == 0 ||
      record_count > kMaxRecordCount) {
    return failure(ParseError::kInvalidShape);
  }
  if (all_zero(bytes.subspan(60, 32)) || all_zero(bytes.subspan(92, 16))) {
    return failure(ParseError::kInvalidIdentity);
  }

  const auto table_offset = read_little_endian<std::uint64_t>(bytes, 28);
  const auto payload_offset = read_little_endian<std::uint64_t>(bytes, 36);
  const auto payload_length = read_little_endian<std::uint64_t>(bytes, 44);
  const auto file_length = read_little_endian<std::uint64_t>(bytes, 52);
  std::uint64_t expected_table_length = 0;
  std::uint64_t expected_payload_length = 0;
  if (!checked_multiply(record_count, kRecordEntrySize,
                        &expected_table_length) ||
      !checked_multiply(record_count, dimension, &expected_payload_length)) {
    return failure(ParseError::kInvalidLayout);
  }
  const auto table_end = table_offset + expected_table_length;
  if (table_end < table_offset || table_offset != kSegmentHeaderSize ||
      payload_offset < table_end || payload_offset % 64U != 0 ||
      payload_length != expected_payload_length ||
      file_length != bytes.size() || payload_offset > file_length ||
      payload_length > file_length - payload_offset ||
      payload_offset + payload_length != file_length) {
    return failure(ParseError::kInvalidLayout);
  }
  if (!all_zero(bytes.subspan(
          static_cast<std::size_t>(table_end),
          static_cast<std::size_t>(payload_offset - table_end)))) {
    return failure(ParseError::kNonCanonicalPadding);
  }

  SegmentView segment;
  segment.channel_ = static_cast<Channel>(channel_value);
  segment.dimension_ = dimension;
  std::copy_n(bytes.begin() + 60, segment.embedding_space_hash_.size(),
              segment.embedding_space_hash_.begin());
  std::copy_n(bytes.begin() + 92, segment.segment_id_.size(),
              segment.segment_id_.begin());
  segment.records_.reserve(record_count);

  for (std::uint32_t index = 0; index < record_count; ++index) {
    const auto offset =
        static_cast<std::size_t>(table_offset + index * kRecordEntrySize);
    RecordMetadata record{
        .record_id = read_little_endian<std::uint64_t>(bytes, offset),
        .asset_id = read_little_endian<std::uint64_t>(bytes, offset + 8),
        .ordinal = read_little_endian<std::uint32_t>(bytes, offset + 16),
    };
    const auto flags = read_little_endian<std::uint32_t>(bytes, offset + 20);
    if (record.record_id == 0 || record.asset_id == 0 || flags != 0 ||
        (segment.channel_ == Channel::kVisual &&
         (record.record_id != record.asset_id || record.ordinal != 0))) {
      return failure(ParseError::kInvalidRecord);
    }
    for (const auto &existing : segment.records_) {
      if (existing.record_id == record.record_id ||
          (segment.channel_ == Channel::kOcrSemantic &&
           existing.asset_id == record.asset_id &&
           existing.ordinal == record.ordinal)) {
        return failure(ParseError::kDuplicateRecord);
      }
    }
    segment.records_.push_back(record);
  }

  const auto *payload =
      reinterpret_cast<const std::int8_t *>(bytes.data() + payload_offset);
  segment.payload_ = std::span<const std::int8_t>(
      payload, static_cast<std::size_t>(payload_length));
  return {.error = ParseError::kNone, .segment = std::move(segment)};
}

} // namespace nayti::search
