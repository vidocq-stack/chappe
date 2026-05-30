package io.vidocq.chappe.bench;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Runner dedicated to the micro-benchmarks validating the "OPTIMS" series of optimizations.
 * Runs: HPACK, MimeTypes, ClasspathLookup, RouterDispatch.
 */
public final class OptimizationsRunner {

    public static void main(String[] args) throws Exception {
        var opt = new OptionsBuilder()
                .include(HpackStaticTableBench.class.getSimpleName())
                .include(MimeTypesBench.class.getSimpleName())
                .include(ClasspathLookupBench.class.getSimpleName())
                .include(RouterDispatchBench.class.getSimpleName())
                .forks(1)
                .warmupIterations(2)
                .warmupTime(org.openjdk.jmh.runner.options.TimeValue.seconds(1))
                .measurementIterations(3)
                .measurementTime(org.openjdk.jmh.runner.options.TimeValue.seconds(1))
                .jvmArgsAppend("--enable-preview")
                .build();
        new Runner(opt).run();
    }

    private OptimizationsRunner() {}
}
