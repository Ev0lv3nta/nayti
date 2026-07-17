#include <cstdlib>

#include "runtime_contract.hpp"

int main() {
    return nayti::ml::runtime_contract_version() == 1 ? EXIT_SUCCESS : EXIT_FAILURE;
}
