package dev.dreamz.llmwizard.ui

import dev.dreamz.llmwizard.core.ollama.OllamaException
import dev.dreamz.llmwizard.core.server.PrivilegeProblem
import dev.dreamz.llmwizard.core.ssh.SshError

/** A failure as the user sees it: what happened and what to do next. */
data class Problem(val title: String, val message: String, val nextStep: String)

/** Maps every typed error to a plain message with a next step (PLAN.md Phase 2). Never includes secrets. */
fun describe(e: Throwable): Problem = when (e) {
    is SshError.Unreachable -> Problem("Can't reach ${e.host}",
        "Nothing answered on that address and port.",
        "Check that the machine is on, that the address is right (on the machine: hostname -I), that SSH is installed (sudo apt install openssh-server) and that this phone is on the same network or VPN.")
    is SshError.AuthFailed -> Problem("Login refused",
        "The server did not accept the user name and password (or the app's key).",
        "Check the user name and password by logging in from a computer: ssh ${e.user}@<address>. If the app's key was removed from ~/.ssh/authorized_keys, log in with the password again.")
    is SshError.HostKeyUnknown -> Problem("New server",
        "This is the first connection to ${e.host}.",
        "Compare the fingerprint with the server's: for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf \"\$f\"; done")
    is SshError.HostKeyChanged -> Problem("The server's identity changed",
        "The key ${e.host} presented (${e.actual.fingerprint}) is not the one stored (${e.expected.fingerprint}). This happens after reinstalling the server, or when something is intercepting the connection.",
        "If you reinstalled the server, open its settings here and choose \"Forget host key\", then connect again and compare the new fingerprint. Otherwise do not connect.")
    is SshError.Timeout -> Problem("Timed out", "The server stopped answering (${e.what}).",
        "Check the network and try again. Long downloads keep running on the server; reopening the server here picks them up.")
    is SshError.Disconnected -> Problem("Connection dropped", "The connection to the server was lost.",
        "Try again. Anything running in the background on the server keeps going and is picked up when you reconnect.")
    is SshError.LocalNetworkPermission -> Problem("Local network access needed",
        "Android blocks apps from reaching devices on your home network until you allow it.",
        "Allow \"Nearby devices / local network\" for this app in its Android settings, then try again.")
    is SshError -> Problem("Connection problem", e.message ?: "Unknown SSH error.", "Try again.")
    is PrivilegeProblem.WrongPassword -> Problem("sudo password refused", e.message!!,
        "Type the password you use for sudo on that machine. If it is right, the user may not be allowed to use sudo: as root, run adduser <user> sudo.")
    is PrivilegeProblem.NoSudo -> Problem("No administrator rights", e.message!!, "On the server as root: apt install sudo && adduser <user> sudo, then log in again.")
    is PrivilegeProblem.PasswordNeeded -> Problem("Password needed", e.message!!, "Enter the sudo password to continue.")
    is OllamaException -> Problem("Ollama said no", e.message ?: "The model server returned an error.", "Check the server's status here, or its log: journalctl -u ollama -e")
    is IllegalArgumentException -> Problem("Not accepted", e.message ?: "A value was not accepted.", "Check what you typed.")
    else -> Problem("Something went wrong", e.message ?: e::class.simpleName ?: "Unknown error.", "Try again. The transcript for this server shows every command that ran.")
}
