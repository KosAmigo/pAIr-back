package com.pair.infra.sandbox;

import com.pair.global.config.PairProperties;

class LocalJvmRunnerTest extends CodeRunnerContractTest {
    @Override
    CodeRunner createRunner() {
        return new LocalJvmRunner(new PairProperties(null, null, null, null, new PairProperties.Run(60), null, null));
    }
}
