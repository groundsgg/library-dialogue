package example;

import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

@EnvTest
class NativePipelineConsumerTest {
  @Test
  void javaCustomNativePipelineAcknowledgesOutputAcceptsInputAndCloses(Env env) {
    NativePipeline.exercise(env, NativePipeline::javaWiring);
  }
}
