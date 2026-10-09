package dev.dreamz.llmwizard.cli

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * `llmwizard <command> <target> [options]` — the whole engine from a terminal (SPEC.md :cli).
 * Targets come from targets.local.properties, or user@host with LLMWIZARD_KEY / _PASSWORD.
 */
fun main(args: Array<String>) {
    val cmd = args.firstOrNull()
    val rest = args.drop(1)
    val code = try {
        runBlocking { Commands(rest).run(cmd) }
    } catch (e: CliError) {
        System.err.println("error: ${e.message}")
        2
    } catch (e: Exception) {
        System.err.println("error: ${e.message ?: e::class.simpleName}")
        if (System.getenv("LLMWIZARD_DEBUG") != null) e.printStackTrace()
        1
    }
    exitProcess(code)
}

class CliError(message: String) : Exception(message)
