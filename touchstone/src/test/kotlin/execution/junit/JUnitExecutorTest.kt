package org.abhijitsarkar.touchstone.execution.junit

import org.abhijitsarkar.touchstone.mockito.any
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.platform.console.ConsoleLauncherExecutionResult
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.item.ExecutionContext
import org.springframework.batch.repeat.RepeatStatus

/**
 * @author Abhijit Sarkar
 */
class JUnitExecutorTest {
    private var jUnitProperties = JUnitProperties()

    private lateinit var junitLauncher: JUnitLauncher
    private lateinit var junitExecutor: JUnitExecutor
    private lateinit var chunkContext: ChunkContext
    private lateinit var executionContext: ExecutionContext
    private lateinit var result: ConsoleLauncherExecutionResult

    @BeforeEach
    fun beforeEach() {
        junitLauncher = mock(JUnitLauncher::class.java)
        chunkContext = mock(ChunkContext::class.java, RETURNS_DEEP_STUBS)
        executionContext = mock(ExecutionContext::class.java)
        `when`(chunkContext.stepContext.stepExecution.executionContext).thenReturn(executionContext)
        junitExecutor = JUnitExecutor(jUnitProperties, junitLauncher)
        result = mock(ConsoleLauncherExecutionResult::class.java)
    }

    @Test
    fun `should complete`() {
        `when`(result.exitCode).thenReturn(0)
        `when`(junitLauncher.launch(any(), any(), any()))
                .thenAnswer {
                    val args = it.getArgument<Array<String>>(2)
                    println(args.contentToString())
                    result
                }

        val status = junitExecutor.execute(null, chunkContext)
        Assertions.assertThat(status).isEqualTo(RepeatStatus.FINISHED)
        verify(executionContext).put(ArgumentMatchers.eq(JUnitExecutor.EXECUTION_RESULT_KEY), any())
    }

    @Test
    fun `should throw exception`() {
        `when`(result.exitCode).thenReturn(1)
        `when`(junitLauncher.launch(any(), any(), any())).thenReturn(result)

        assertThrows(TestFailedException::class.java, { junitExecutor.execute(null, chunkContext) })
        verify(executionContext).put(ArgumentMatchers.eq(JUnitExecutor.EXECUTION_RESULT_KEY), any())
    }
}