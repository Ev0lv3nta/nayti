#include "exact_search.hpp"
#include "mapped_segment.hpp"
#include "segment_format.hpp"
#include "sha256.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <random>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace {

using nayti::search::Channel;
using nayti::search::ParseError;
using nayti::search::RecordMetadata;
using nayti::search::SearchHit;

int failures = 0;

void expect(bool condition, std::string_view message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << '\n';
    ++failures;
  }
}

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

std::vector<std::byte> make_segment(Channel channel, std::uint32_t dimension,
                                    std::span<const RecordMetadata> records,
                                    std::span<const std::int8_t> vectors) {
  const std::size_t table_end =
      nayti::search::kSegmentHeaderSize +
      records.size() * nayti::search::kRecordEntrySize;
  const std::size_t payload_offset = align_up(table_end, 64);
  std::vector<std::byte> bytes(payload_offset + vectors.size(), std::byte{0});
  constexpr std::array<char, 8> magic = {'N', 'A', 'Y', 'T',
                                         'I', 'V', 'E', 'C'};
  for (std::size_t index = 0; index < magic.size(); ++index) {
    bytes[index] = static_cast<std::byte>(magic[index]);
  }
  write_little_endian<std::uint16_t>(bytes, 8, 1);
  write_little_endian<std::uint16_t>(bytes, 10, 128);
  bytes[12] = static_cast<std::byte>(channel);
  bytes[13] = std::byte{1};
  bytes[14] = std::byte{1};
  write_little_endian<std::uint32_t>(bytes, 16, dimension);
  write_little_endian<std::uint32_t>(
      bytes, 20, static_cast<std::uint32_t>(records.size()));
  write_little_endian<std::uint16_t>(bytes, 24, 24);
  write_little_endian<std::uint64_t>(bytes, 28, 128);
  write_little_endian<std::uint64_t>(bytes, 36, payload_offset);
  write_little_endian<std::uint64_t>(bytes, 44, vectors.size());
  write_little_endian<std::uint64_t>(bytes, 52, bytes.size());
  bytes[60] = std::byte{0x5a};
  bytes[92] = std::byte{0xa5};

  for (std::size_t index = 0; index < records.size(); ++index) {
    const std::size_t offset = 128 + index * 24;
    write_little_endian<std::uint64_t>(bytes, offset, records[index].record_id);
    write_little_endian<std::uint64_t>(bytes, offset + 8,
                                       records[index].asset_id);
    write_little_endian<std::uint32_t>(bytes, offset + 16,
                                       records[index].ordinal);
  }
  for (std::size_t index = 0; index < vectors.size(); ++index) {
    bytes[payload_offset + index] = static_cast<std::byte>(vectors[index]);
  }
  return bytes;
}

void expect_error(const std::vector<std::byte> &bytes, ParseError error,
                  std::string_view message) {
  const auto result = nayti::search::parse_segment(bytes);
  expect(!result && result.error == error, message);
}

void test_golden_and_corruption() {
  const std::array records = {
      RecordMetadata{.record_id = 11, .asset_id = 11, .ordinal = 0},
      RecordMetadata{.record_id = 12, .asset_id = 12, .ordinal = 0},
  };
  const std::array<std::int8_t, 8> vectors = {1, -2, 3, -4, 5, 6, -7, 8};
  const auto golden = make_segment(Channel::kVisual, 4, records, vectors);
  const auto parsed = nayti::search::parse_segment(golden);
  expect(parsed && parsed.segment->records().size() == 2,
         "golden segment parses");
  expect(parsed && parsed.segment->dimension() == 4,
         "golden dimension is retained");
  expect(parsed && parsed.segment->embedding_space_hash()[0] == std::byte{0x5a},
         "embedding-space identity is retained");
  expect(parsed && parsed.segment->segment_id()[0] == std::byte{0xa5},
         "segment identity is retained");
  expect(parsed && parsed.segment->vector(1)[2] == -7,
         "payload remains zero-copy readable");

  expect_error(std::vector<std::byte>(golden.begin(), golden.begin() + 127),
               ParseError::kTruncated, "truncated header is rejected");

  auto changed = golden;
  changed[0] = std::byte{'X'};
  expect_error(changed, ParseError::kInvalidMagic, "wrong magic is rejected");
  changed = golden;
  write_little_endian<std::uint16_t>(changed, 8, 2);
  expect_error(changed, ParseError::kUnsupportedVersion,
               "unknown version is rejected");
  changed = golden;
  write_little_endian<std::uint16_t>(changed, 10, 64);
  expect_error(changed, ParseError::kInvalidHeader,
               "wrong header size is rejected");
  changed = golden;
  write_little_endian<std::uint16_t>(changed, 24, 16);
  expect_error(changed, ParseError::kInvalidHeader,
               "wrong record size is rejected");
  changed = golden;
  write_little_endian<std::uint16_t>(changed, 26, 1);
  expect_error(changed, ParseError::kInvalidHeader,
               "reserved scalar is rejected");
  changed = golden;
  changed[15] = std::byte{1};
  expect_error(changed, ParseError::kInvalidHeader,
               "unknown header flags are rejected");
  changed = golden;
  changed[108] = std::byte{1};
  expect_error(changed, ParseError::kInvalidHeader,
               "non-zero reserved header is rejected");
  changed = golden;
  changed[12] = std::byte{9};
  expect_error(changed, ParseError::kInvalidChannel,
               "unknown channel is rejected");
  changed = golden;
  changed[13] = std::byte{2};
  expect_error(changed, ParseError::kInvalidEncoding,
               "unknown dtype is rejected");
  changed = golden;
  changed[14] = std::byte{2};
  expect_error(changed, ParseError::kInvalidEncoding,
               "unknown metric is rejected");
  changed = golden;
  write_little_endian<std::uint32_t>(changed, 16, 0);
  expect_error(changed, ParseError::kInvalidShape,
               "zero dimension is rejected");
  changed = golden;
  write_little_endian<std::uint32_t>(changed, 20, 257);
  expect_error(changed, ParseError::kInvalidShape,
               "oversized record count is rejected");
  changed = golden;
  changed[60] = std::byte{0};
  expect_error(changed, ParseError::kInvalidIdentity,
               "zero embedding-space hash is rejected");
  changed = golden;
  changed[92] = std::byte{0};
  expect_error(changed, ParseError::kInvalidIdentity,
               "nil segment id is rejected");
  changed = golden;
  write_little_endian<std::uint64_t>(changed, 28, 64);
  expect_error(changed, ParseError::kInvalidLayout,
               "wrong table offset is rejected");
  changed = golden;
  write_little_endian<std::uint64_t>(changed, 36, 129);
  expect_error(changed, ParseError::kInvalidLayout,
               "unaligned payload is rejected");
  changed = golden;
  write_little_endian<std::uint64_t>(changed, 44, vectors.size() - 1);
  expect_error(changed, ParseError::kInvalidLayout,
               "wrong payload length is rejected");
  changed = golden;
  write_little_endian<std::uint64_t>(changed, 52, golden.size() - 1);
  expect_error(changed, ParseError::kInvalidLayout,
               "wrong file length is rejected");
  changed = golden;
  changed[176] = std::byte{1};
  expect_error(changed, ParseError::kNonCanonicalPadding,
               "non-zero padding is rejected");
  changed = golden;
  write_little_endian<std::uint64_t>(changed, 128 + 24, 11);
  write_little_endian<std::uint64_t>(changed, 128 + 24 + 8, 11);
  expect_error(changed, ParseError::kDuplicateRecord,
               "duplicate record id is rejected");
  changed = golden;
  write_little_endian<std::uint32_t>(changed, 128 + 20, 1);
  expect_error(changed, ParseError::kInvalidRecord,
               "record flags are rejected");
  changed = golden;
  write_little_endian<std::uint64_t>(changed, 128, 0);
  expect_error(changed, ParseError::kInvalidRecord,
               "zero record id is rejected");
  changed = golden;
  write_little_endian<std::uint64_t>(changed, 128 + 8, 99);
  expect_error(changed, ParseError::kInvalidRecord,
               "visual record/asset mismatch is rejected");
  changed = golden;
  changed.push_back(std::byte{0});
  expect_error(changed, ParseError::kInvalidLayout,
               "trailing byte is rejected");
}

bool keep_odd_assets(std::uint64_t asset_id, void *context) {
  auto *calls = static_cast<std::size_t *>(context);
  ++*calls;
  return asset_id % 2U == 1U;
}

bool better(const SearchHit &left, const SearchHit &right) {
  return left.score > right.score ||
         (left.score == right.score && left.record_id < right.record_id);
}

void test_top_k_contract() {
  const std::array records = {
      RecordMetadata{.record_id = 1, .asset_id = 1, .ordinal = 0},
      RecordMetadata{.record_id = 2, .asset_id = 2, .ordinal = 0},
      RecordMetadata{.record_id = 3, .asset_id = 3, .ordinal = 0},
      RecordMetadata{.record_id = 4, .asset_id = 4, .ordinal = 0},
  };
  const std::array<std::int8_t, 8> vectors = {1, 0, 1, 0, 1, 0, -1, 0};
  const auto bytes = make_segment(Channel::kVisual, 2, records, vectors);
  const auto parsed = nayti::search::parse_segment(bytes);
  const std::array<std::int8_t, 2> query = {1, 0};
  nayti::search::ExactTopKScanner scanner(
      query, 2, parsed.segment->channel(),
      parsed.segment->embedding_space_hash());
  std::size_t filter_calls = 0;
  expect(scanner.scan(*parsed.segment, keep_odd_assets, &filter_calls) ==
             nayti::search::ScanError::kNone,
         "eligible segment scans");
  const auto results = scanner.results();
  expect(filter_calls == records.size(), "eligibility runs once per record");
  expect(results.size() == 2 && results[0].record_id == 1 &&
             results[1].record_id == 3,
         "tie order is record id ascending");
  expect(scanner.retained_hit_count() <= 2, "scanner retains at most K hits");

  auto incompatible_bytes = bytes;
  incompatible_bytes[60] = std::byte{0x6b};
  const auto incompatible = nayti::search::parse_segment(incompatible_bytes);
  expect(scanner.scan(*incompatible.segment) ==
             nayti::search::ScanError::kIncompatibleSegment,
         "scanner rejects a different embedding space");

  const std::array<std::byte, 32> empty_space{};
  nayti::search::ExactTopKScanner invalid_space(query, 2, Channel::kVisual,
                                                empty_space);
  expect(invalid_space.status() ==
             nayti::search::ScanError::kInvalidEmbeddingSpace,
         "scanner rejects a nil query embedding space");
}

void test_semantic_record_invariants() {
  const std::array valid_records = {
      RecordMetadata{.record_id = 101, .asset_id = 7, .ordinal = 0},
      RecordMetadata{.record_id = 102, .asset_id = 7, .ordinal = 1},
  };
  const std::array<std::int8_t, 4> vectors = {1, 2, 3, 4};
  const auto valid =
      make_segment(Channel::kOcrSemantic, 2, valid_records, vectors);
  expect(static_cast<bool>(nayti::search::parse_segment(valid)),
         "semantic channel accepts distinct chunks for one asset");

  const std::array duplicate_pair = {
      RecordMetadata{.record_id = 101, .asset_id = 7, .ordinal = 0},
      RecordMetadata{.record_id = 102, .asset_id = 7, .ordinal = 0},
  };
  const auto invalid =
      make_segment(Channel::kOcrSemantic, 2, duplicate_pair, vectors);
  expect_error(invalid, ParseError::kDuplicateRecord,
               "semantic channel rejects duplicate asset/ordinal pairs");
}

void test_random_parity() {
  std::mt19937 generator(0x4e415954U);
  std::uniform_int_distribution<int> value_distribution(-127, 127);
  std::uniform_int_distribution<int> count_distribution(1, 256);
  std::uniform_int_distribution<int> dimension_distribution(1, 96);
  std::uniform_int_distribution<int> k_distribution(1, 64);

  for (int trial = 0; trial < 200; ++trial) {
    const auto count = static_cast<std::size_t>(count_distribution(generator));
    const auto dimension =
        static_cast<std::size_t>(dimension_distribution(generator));
    const auto k = std::min<std::size_t>(k_distribution(generator), count);
    std::vector<RecordMetadata> records;
    std::vector<std::int8_t> vectors(count * dimension);
    std::vector<std::int8_t> query(dimension);
    records.reserve(count);
    for (std::size_t index = 0; index < count; ++index) {
      records.push_back(
          {.record_id = index + 1, .asset_id = index + 1, .ordinal = 0});
    }
    for (auto &value : vectors) {
      value = static_cast<std::int8_t>(value_distribution(generator));
    }
    for (auto &value : query) {
      value = static_cast<std::int8_t>(value_distribution(generator));
    }

    const auto bytes =
        make_segment(Channel::kVisual, static_cast<std::uint32_t>(dimension),
                     records, vectors);
    const auto parsed = nayti::search::parse_segment(bytes);
    expect(static_cast<bool>(parsed), "random valid segment parses");
    if (!parsed) {
      continue;
    }

    std::vector<SearchHit> reference;
    reference.reserve(count);
    for (std::size_t index = 0; index < count; ++index) {
      reference.push_back({
          .record_id = records[index].record_id,
          .asset_id = records[index].asset_id,
          .ordinal = 0,
          .score = nayti::search::dot_product_scalar(
              std::span<const std::int8_t>(vectors).subspan(index * dimension,
                                                            dimension),
              query),
      });
    }
    std::sort(reference.begin(), reference.end(), better);
    reference.resize(k);

    nayti::search::ExactTopKScanner scanner(
        query, k, parsed.segment->channel(),
        parsed.segment->embedding_space_hash());
    expect(scanner.scan(*parsed.segment) == nayti::search::ScanError::kNone,
           "random segment scans");
    expect(scanner.results() == reference,
           "bounded heap equals full scalar sort");
  }
}

void test_accumulator_bound() {
  std::vector<std::int8_t> values(nayti::search::kMaxDimension, 127);
  expect(nayti::search::dot_product_scalar(values, values) == 66'064'384,
         "maximum v1 dot product stays exact in int32");
}

std::string digest_hex(const std::array<std::byte, 32> &digest) {
  constexpr char hex[] = "0123456789abcdef";
  std::string result;
  result.reserve(digest.size() * 2);
  for (const auto value : digest) {
    const auto byte = std::to_integer<unsigned int>(value);
    result.push_back(hex[byte >> 4U]);
    result.push_back(hex[byte & 0x0fU]);
  }
  return result;
}

void test_sha256_vectors() {
  const std::array<std::byte, 0> empty{};
  expect(digest_hex(nayti::search::sha256(empty)) ==
             "e3b0c44298fc1c149afbf4c8996fb924"
             "27ae41e4649b934ca495991b7852b855",
         "SHA-256 empty vector matches the standard digest");
  constexpr std::string_view abc = "abc";
  expect(digest_hex(nayti::search::sha256(std::as_bytes(std::span<const char>(
             abc.data(), abc.size())))) == "ba7816bf8f01cfea414140de5dae2223"
                                           "b00361a396177a9cb410ff61f20015ad",
         "SHA-256 abc vector matches the standard digest");
  constexpr std::string_view two_block =
      "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
  expect(digest_hex(nayti::search::sha256(std::as_bytes(
             std::span<const char>(two_block.data(), two_block.size())))) ==
             "248d6a61d20638b8e5c026930c3e6039"
             "a33ce45964ff2167f6ecedd419db06c1",
         "SHA-256 two-block vector validates boundary padding");
}

void write_file(const std::filesystem::path &path,
                std::span<const std::byte> bytes) {
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  output.write(reinterpret_cast<const char *>(bytes.data()),
               static_cast<std::streamsize>(bytes.size()));
  output.close();
  expect(output.good(), "proof fixture is written completely");
}

void test_read_only_mapping_and_path_replacement() {
  const auto directory = std::filesystem::current_path() / "vector-proof-files";
  std::filesystem::remove_all(directory);
  std::filesystem::create_directories(directory);
  const auto segment_path = directory / "active.naytivec";
  const auto replacement_path = directory / "replacement.naytivec";

  const std::array records = {
      RecordMetadata{.record_id = 41, .asset_id = 41, .ordinal = 0},
  };
  const std::array<std::int8_t, 3> original_vector = {7, 8, 9};
  const std::array<std::int8_t, 3> replacement_vector = {70, 80, 90};
  const auto original =
      make_segment(Channel::kVisual, 3, records, original_vector);
  const auto replacement =
      make_segment(Channel::kVisual, 3, records, replacement_vector);
  const auto original_hash = nayti::search::sha256(original);
  const auto replacement_hash = nayti::search::sha256(replacement);
  write_file(segment_path, original);

  auto mapped = nayti::search::map_segment(segment_path.string(),
                                           original.size(), original_hash);
  expect(mapped && mapped.mapped->byte_length() == original.size(),
         "valid segment opens through read-only mmap");
  expect(mapped && mapped.mapped->segment().vector(0)[0] == 7,
         "mapping exposes the original inode payload");

  write_file(replacement_path, replacement);
  std::filesystem::rename(replacement_path, segment_path);
  expect(mapped && mapped.mapped->segment().vector(0)[0] == 7,
         "path replacement cannot retarget an open mapping");
  auto stale_manifest = nayti::search::map_segment(
      segment_path.string(), replacement.size(), original_hash);
  expect(!stale_manifest &&
             stale_manifest.error == nayti::search::MapError::kHashMismatch,
         "replacement is rejected against the old manifest hash");
  auto remapped = nayti::search::map_segment(
      segment_path.string(), replacement.size(), replacement_hash);
  expect(remapped && remapped.mapped->segment().vector(0)[0] == 70,
         "a new open observes the replacement inode");

  const std::array<std::byte, 64> truncated{};
  write_file(directory / "truncated.naytivec", truncated);
  const auto truncated_hash = nayti::search::sha256(truncated);
  const auto invalid =
      nayti::search::map_segment((directory / "truncated.naytivec").string(),
                                 truncated.size(), truncated_hash);
  expect(!invalid &&
             invalid.error == nayti::search::MapError::kInvalidSegment &&
             invalid.parse_error == ParseError::kTruncated,
         "truncated mapped file is rejected before scan");

  std::filesystem::create_symlink(segment_path, directory / "link.naytivec");
  const auto symlink =
      nayti::search::map_segment((directory / "link.naytivec").string(),
                                 replacement.size(), replacement_hash);
  expect(!symlink && symlink.error == nayti::search::MapError::kOpenFailed,
         "symlink path is rejected");
  std::filesystem::remove_all(directory);
}

} // namespace

int main() {
  test_golden_and_corruption();
  test_top_k_contract();
  test_semantic_record_invariants();
  test_random_parity();
  test_accumulator_bound();
  test_sha256_vectors();
  test_read_only_mapping_and_path_replacement();
  if (failures != 0) {
    std::cerr << failures << " vector proof assertion(s) failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "Vector segment v1 and scalar exact top-K proof passed.\n";
  return EXIT_SUCCESS;
}
