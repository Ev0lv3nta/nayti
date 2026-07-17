#pragma once

#include <cstdint>

namespace nayti::ml {

inline constexpr std::uint32_t kRuntimeContractVersion = 1;

std::uint32_t runtime_contract_version() noexcept;

}  // namespace nayti::ml
