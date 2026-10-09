# llmwizard privacy policy (draft)

*Draft written 2026-10-10 for release 1 (setup, chat and server management). The app's name and
the developer contact still need filling in. Release 2 (the assistant: texts, calls, contacts,
calendar) will need this rewritten before it ships.*

## The short version

llmwizard sets up and runs an AI model on a computer **you** own, and talks to it over SSH.
We (the developer) have no server, no account system, no analytics and no ads. We never see your
servers, your chats or your data.

## What the app stores, and where

Everything below stays on your phone, in the app's private storage. The app turns off Android
backup, so none of it is copied to Google Drive or moved to a new phone.

- **Your servers:** the name you gave each, its address, port and user name, and the fingerprint
  of its SSH host key (to detect if the server is ever swapped).
- **The app's SSH key:** generated on your phone. The private half is encrypted with a key held by
  Android's hardware-backed keystore and never leaves the phone. The public half is placed on
  your servers only when you tap "Add the key".
- **Passwords: never stored.** A login or sudo password is sent to your server over SSH for the
  one action that needs it, then erased from memory.
- **The transcript:** for each server, the scripts the app ran there, their exit codes and the
  last part of their output. It never contains passwords.
- **Chat history** with your model, and speed measurements (tokens per second).

To delete it: remove a server in the app, or uninstall the app.

## What the app sends, and to whom

- **To your own servers, over SSH:** the commands you approved (each is shown before it runs),
  your chat messages, and requests to your AI model. This traffic is encrypted and goes only to
  the machines you added.
- **From your server (not your phone) to the internet:** when you install or update, your server
  downloads Ollama from GitHub and models from Ollama's registry. That is between your server and
  those services.
- **To Google Play:** if you buy Pro, Google Play handles the payment. We receive no payment
  details.
- **Recipe updates (later versions):** the app may download a signed file of install steps and
  the model list from the developer's published location. It sends nothing about you or your
  servers to fetch it.

Nothing else. No analytics, crash reporting, advertising or tracking SDKs are included.

## Sharing your model with other devices

If you turn on "Share with other apps", your model becomes reachable by other devices on the
network you choose. Ollama itself has no password, so anything on that network can use it. The
app says so before you turn it on, and "Go back to private" undoes it.

## Diagnostic reports

The app can build a report to help with support. It is shown to you in full, has addresses, host
and user names hidden by default, and is only copied to your clipboard; the app never sends it.

## Children

The app is not directed at children.

## Contact

<developer contact to fill in>
