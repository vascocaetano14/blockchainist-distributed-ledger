#!/usr/bin/env bash
set -euo pipefail

################################################
### PATHS (feel free to tweak paths accordingly)
MVN_CLIENT_MODULE=client
TESTS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)" # infers this script's location
INPUTS="${TESTS_DIR}/inputs"
EXPECTED_OUTPUTS="${TESTS_DIR}/outputs"
TEST_OUTPUTS="${TESTS_DIR}/test-outputs"
MVN_ROOT_DIR="$(cd -- "${TESTS_DIR}/.." && pwd)" # assumes tests directory is inside the maven project's root directory
MVN_ROOT_POM="$MVN_ROOT_DIR/pom.xml"
################################################
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'
################################################

exec_client() {
    mvn --quiet -f "$MVN_ROOT_POM" -pl client exec:java < $1 > $2
}

################################################

echo "Running tests"

rm -rf $TEST_OUTPUTS
mkdir -p $TEST_OUTPUTS

i=1
while true; do
    TEST=$(printf "%02d" $i);
    if [ -e ${INPUTS}/input$TEST.txt ]; then 
        exec_client "${INPUTS}/input${TEST}.txt" "${TEST_OUTPUTS}/out${TEST}.txt"
        if diff -u "${TEST_OUTPUTS}/out${TEST}.txt" "${EXPECTED_OUTPUTS}/out${TEST}.txt"; then
            printf "${GREEN}[%s] TEST PASSED${NC}\n" "${TEST}"
        else
            printf "${RED}[%s] TEST FAILED${NC}\n" "${TEST}"
        fi
        i=$((i+1))
    else
        break
    fi
done

echo "Check the outputs of each test in ${TEST_OUTPUTS}."