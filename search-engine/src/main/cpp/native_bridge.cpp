#include <jni.h>

#include "exact_search.hpp"
#include "mapped_segment.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace {

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
  if (path == nullptr || expected_sha256 == nullptr || expected_length <= 0 ||
      environment->GetArrayLength(expected_sha256) != 32) {
    return -1;
  }
  std::array<jbyte, 32> java_hash{};
  environment->GetByteArrayRegion(expected_sha256, 0, java_hash.size(),
                                  java_hash.data());
  if (environment->ExceptionCheck()) {
    return -1;
  }
  std::array<std::byte, 32> native_hash{};
  for (std::size_t index = 0; index < native_hash.size(); ++index) {
    native_hash[index] =
        static_cast<std::byte>(static_cast<std::uint8_t>(java_hash[index]));
  }
  const char *path_chars = environment->GetStringUTFChars(path, nullptr);
  if (path_chars == nullptr) {
    return -1;
  }
  const std::string native_path(path_chars);
  environment->ReleaseStringUTFChars(path, path_chars);
  auto mapped = nayti::search::map_segment(
      native_path, static_cast<std::uint64_t>(expected_length), native_hash);
  if (!mapped) {
    return -1;
  }
  return static_cast<jint>(mapped.mapped->segment().records().size());
}
