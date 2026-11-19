package com.pair.run;

import com.pair.config.PairProperties;

class LocalJvmRunnerTest extends CodeRunnerContractTest {
    @Override
    CodeRunner createRunner() {
        return new LocalJvmRunner(new PairProperties(null, null, null, null, new PairProperties.Run(60), null, null));
    }
}
