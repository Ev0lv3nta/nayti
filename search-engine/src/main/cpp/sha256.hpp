#pragma once

#include <array>
#include <cstddef>
#include <span>

namespace nayti::search {

std::array<std::byte, 32> sha256(std::span<const std::byte> bytes);

} // namespace nayti::search
