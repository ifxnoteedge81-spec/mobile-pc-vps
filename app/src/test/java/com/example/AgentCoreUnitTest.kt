package com.example

import com.example.runtime.LocalPythonEngine
import com.example.runtime.VirtualWorkspaceManager
import com.example.security.CommandSecurityGuard
import com.example.security.CommandValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentCoreUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun commandSecurityGuard_blocksDestructiveAndAllowsSafe() {
        assertTrue(CommandSecurityGuard.validateShellCommand("ls -la") is CommandValidationResult.Allowed)
        assertTrue(CommandSecurityGuard.validateShellCommand("rm -rf /") is CommandValidationResult.Blocked)
        assertTrue(CommandSecurityGuard.validateShellCommand("rm old_file.txt") is CommandValidationResult.RequiresConfirmation)
        assertTrue(CommandSecurityGuard.containsSensitiveCredential("my otp code is 1234"))
    }

    @Test
    fun virtualWorkspace_preventsPathTraversalAndCreatesFiles() {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("files"))
        workspace.writeTextFile("workspace/test_note.txt", "hello agent").getOrThrow()
        val readBack = workspace.readTextFile("workspace/test_note.txt").getOrThrow()
        assertEquals("hello agent", readBack)

        var threw = false
        try {
            workspace.resolveSafe("../../etc/passwd")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("Expected path traversal rejection", threw)
    }

    @Test
    fun localPythonEngine_executesRealPythonCode() {
        val workspace = VirtualWorkspaceManager(tempFolder.newFolder("py_files"))
        val python = LocalPythonEngine(workspace)
        val code = """
            def fib(n):
                if n <= 1:
                    return n
                return fib(n - 1) + fib(n - 2)
            print("fib6=", fib(6))
        """.trimIndent()
        val res = python.executeScript(code)
        assertTrue("Python execution failed: ${res.stderr}", res.success)
        assertTrue("Expected fib6= 8 in stdout but got: ${res.stdout}", res.stdout.contains("fib6= 8"))
    }
}
