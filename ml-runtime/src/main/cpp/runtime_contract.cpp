#include "runtime_contract.hpp"

namespace nayti::ml {

std::uint32_t runtime_contract_version() noexcept {
    return kRuntimeContractVersion;
}

}  // namespace nayti::ml
