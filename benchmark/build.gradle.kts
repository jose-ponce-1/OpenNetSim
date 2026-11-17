// Microbenchmarks JMH (kotlinx-benchmark) del camino de cada paquete en :core. No forma parte de la app.
//   ./gradlew :benchmark:benchmark        medición completa (~5 min)
//   ./gradlew :benchmark:smokeBenchmark   comprobación rápida de que los benchmarks corren
//   ./gradlew :benchmark:allocationBenchmark   como benchmark, más los bytes reservados por operación
// Resultados JSON en benchmark/build/reports/benchmarks/.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// JMH genera subclases de las clases @State: Kotlin las haría final.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.benchmark.runtime)
}

benchmark {
    targets {
        register("main")
    }
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "ns"
        }
        register("smoke") {
            warmups = 1
            iterations = 1
            iterationTime = 200
            iterationTimeUnit = "ms"
            mode = "avgt"
            outputTimeUnit = "ns"
        }
    }
}

// Igual que `benchmark` y además bytes reservados por paquete (`-prof gc` de JMH, que kotlinx-benchmark
// no expone): en Android la presión sobre el GC pesa tanto como el tiempo.
val allocationReport = layout.buildDirectory.file("reports/benchmarks/allocations.json")
tasks.register<JavaExec>("allocationBenchmark") {
    group = "benchmark"
    description = "Ejecuta los benchmarks con el profiler de GC de JMH (ns/op y B/op)."
    val jar = tasks.named("mainBenchmarkJar")
    dependsOn(jar)
    classpath(jar.map { it.outputs.files })
    mainClass.set("org.openjdk.jmh.Main")
    args(
        "-wi", "5", "-i", "5", "-w", "1s", "-r", "1s", "-f", "1",
        "-bm", "avgt", "-tu", "ns", "-prof", "gc",
        "-rf", "json", "-rff", allocationReport.get().asFile.absolutePath
    )
    doFirst { allocationReport.get().asFile.parentFile.mkdirs() }
}
