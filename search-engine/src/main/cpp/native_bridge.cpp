#include <jni.h>

#include "exact_search.hpp"
#include "mapped_segment.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace {

constexpr jsize kSha256ByteCount = 32;

bool copy_sha256(JNIEnv *environment, jbyteArray source,
                 std::array<std::byte, 32> *destination) {
  if (source == nullptr ||
      environment->GetArrayLength(source) != kSha256ByteCount) {
    return false;
  }
  std::array<jbyte, kSha256ByteCount> java_hash{};
  environment->GetByteArrayRegion(source, 0, java_hash.size(),
                                  java_hash.data());
  if (environment->ExceptionCheck()) {
    return false;
  }
  for (std::size_t index = 0; index < destination->size(); ++index) {
    (*destination)[index] =
        static_cast<std::byte>(static_cast<std::uint8_t>(java_hash[index]));
  }
  return true;
}

bool copy_path(JNIEnv *environment, jstring source, std::string *destination) {
  if (source == nullptr) {
    return false;
  }
  const char *characters = environment->GetStringUTFChars(source, nullptr);
  if (characters == nullptr) {
    return false;
  }
  *destination = characters;
  environment->ReleaseStringUTFChars(source, characters);
  return !destination->empty();
}

bool decode_channel(jint code, nayti::search::Channel *channel) {
  switch (code) {
  case static_cast<jint>(nayti::search::Channel::kVisual):
    *channel = nayti::search::Channel::kVisual;
    return true;
  case static_cast<jint>(nayti::search::Channel::kOcrSemantic):
    *channel = nayti::search::Channel::kOcrSemantic;
    return true;
  default:
    return false;
  }
}

std::uint32_t next_random(std::uint32_t *state) {
  *state ^= *state << 13U;
  *state ^= *state >> 17U;
  *state ^= *state << 5U;
  return *state;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_app_nayti_search_engine_NativeVectorIndex_contractVersion(JNIEnv *,
                                                               jobject) {
  return static_cast<jint>(nayti::search::kSegmentFormatVersion);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_nayti_search_engine_NativeVectorIndex_optimizedDotMatchesScalar(
    JNIEnv *, jobject, jint seed, jint cases) {
  if (cases <= 0 || cases > 10'000) {
    return JNI_FALSE;
  }
  std::uint32_t state = static_cast<std::uint32_t>(seed);
  if (state == 0) {
    state = 0x4e415954U;
  }
  std::vector<std::int8_t> left(nayti::search::kMaxDimension);
  std::vector<std::int8_t> right(nayti::search::kMaxDimension);
  for (jint iteration = 0; iteration < cases; ++iteration) {
    const std::size_t dimension =
        next_random(&state) % nayti::search::kMaxDimension + 1U;
    for (std::size_t index = 0; index < dimension; ++index) {
      left[index] = static_cast<std::int8_t>(
          static_cast<int>(next_random(&state) % 255U) - 127);
      right[index] = static_cast<std::int8_t>(
          static_cast<int>(next_random(&state) % 255U) - 127);
    }
    const auto left_view = std::span<const std::int8_t>(left).first(dimension);
    const auto right_view =
        std::span<const std::int8_t>(right).first(dimension);
    if (nayti::search::dot_product(left_view, right_view) !=
        nayti::search::dot_product_scalar(left_view, right_view)) {
      return JNI_FALSE;
    }
  }
  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_nayti_search_engine_NativeVectorIndex_mappedRecordCount(
    JNIEnv *environment, jobject, jstring path, jlong expected_length,
    jbyteArray expected_sha256) {
  if (expected_length <= 0) {
    return -1;
  }
  std::array<std::byte, 32> native_hash{};
  std::string native_path;
  if (!copy_sha256(environment, expected_sha256, &native_hash) ||
      !copy_path(environment, path, &native_path)) {
    return -1;
  }
  auto mapped = nayti::search::map_segment(
      native_path, static_cast<std::uint64_t>(expected_length), native_hash);
  if (!mapped) {
    return -1;
  }
  return static_cast<jint>(mapped.mapped->segment().records().size());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_app_nayti_search_engine_NativeVectorIndex_exactTopKPacked(
    JNIEnv *environment, jobject, jstring path, jlong expected_length,
    jbyteArray expected_sha256, jbyteArray query, jint k, jint channel_code,
    jbyteArray embedding_space_hash) {
  if (expected_length <= 0 || query == nullptr || k <= 0 ||
      static_cast<std::size_t>(k) > nayti::search::kMaxTopK) {
    return nullptr;
  }
  const jsize query_length = environment->GetArrayLength(query);
  if (query_length <= 0 ||
      static_cast<std::uint32_t>(query_length) > nayti::search::kMaxDimension) {
    return nullptr;
  }

  std::array<std::byte, 32> segment_hash{};
  std::array<std::byte, 32> embedding_hash{};
  std::string native_path;
  nayti::search::Channel channel;
  if (!copy_sha256(environment, expected_sha256, &segment_hash) ||
      !copy_sha256(environment, embedding_space_hash, &embedding_hash) ||
      !copy_path(environment, path, &native_path) ||
      !decode_channel(channel_code, &channel)) {
    return nullptr;
  }

  std::vector<jbyte> java_query(static_cast<std::size_t>(query_length));
  environment->GetByteArrayRegion(query, 0, query_length, java_query.data());
  if (environment->ExceptionCheck()) {
    return nullptr;
  }
  std::vector<std::int8_t> native_query(java_query.size());
  for (std::size_t index = 0; index < java_query.size(); ++index) {
    native_query[index] = static_cast<std::int8_t>(java_query[index]);
  }

  auto mapped = nayti::search::map_segment(
      native_path, static_cast<std::uint64_t>(expected_length), segment_hash);
  if (!mapped) {
    return nullptr;
  }
  nayti::search::ExactTopKScanner scanner(native_query,
                                          static_cast<std::size_t>(k), channel,
                                          embedding_hash);
  if (scanner.status() != nayti::search::ScanError::kNone ||
      scanner.scan(mapped.mapped->segment()) !=
          nayti::search::ScanError::kNone) {
    return nullptr;
  }

  const auto hits = scanner.results();
  constexpr std::size_t kFieldsPerHit = 4;
  std::vector<jlong> packed(hits.size() * kFieldsPerHit);
  for (std::size_t index = 0; index < hits.size(); ++index) {
    const auto &hit = hits[index];
    const std::size_t offset = index * kFieldsPerHit;
    packed[offset] = static_cast<jlong>(hit.record_id);
    packed[offset + 1] = static_cast<jlong>(hit.asset_id);
    packed[offset + 2] = static_cast<jlong>(hit.ordinal);
    packed[offset + 3] = static_cast<jlong>(hit.score);
  }
  auto result = environment->NewLongArray(static_cast<jsize>(packed.size()));
  if (result == nullptr) {
    return nullptr;
  }
  environment->SetLongArrayRegion(result, 0, static_cast<jsize>(packed.size()),
                                  packed.data());
  if (environment->ExceptionCheck()) {
    return nullptr;
  }
  return result;
}
