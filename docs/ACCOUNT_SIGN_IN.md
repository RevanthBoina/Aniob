# Account Sign-In (ChatGPT / Codex Integration)

Aniob supports signing in with an account (OAuth 2.0 PKCE with OpenAI / ChatGPT backend) to route LLM model steps through the user's account plan rather than requiring manual API keys.

## Architecture

1. **Protocol & State Invariants**:
   - `AniobAuthRepository` (`core-agent/auth`) is the sole custodian of authentication tokens and session lifecycle.
   - External consumers obtain short-lived tokens via `obtainAccess()` (`AniobAccessGrant`). Tokens, refresh tokens, auth codes, and PKCE verifiers are never persisted in plaintext, logged, or exposed in UI (`AniobSecretRedactor`).
   - Loopback redirect receiver (`AniobLoopbackCallbackListener`) strictly binds `127.0.0.1` on port 1455 with constant-time state validation (`AniobAuthCallbackParser`).
   - Encryption at rest uses Android Keystore-backed AES-GCM (`AniobKeystoreCipher`) with authenticated additional data.

2. **Routing & Provider Contract**:
   - `AniobAccountModelClient` (`app/auth`) implements `AniobCloudLlmProvider` and `AniobActionStepProvider`.
   - `AniobCloudSourceSelector` explicitly manages cloud provider preference between `GATEWAY` (OmniRoute) and `ACCOUNT` (ChatGPT). If an account is selected but not signed in, it fails fast as unavailable and never silently downgrades.
   - Model requests use the OpenAI Responses API streaming format (`AniobResponsesRequest`, `AniobSseParser`).

3. **Configuration**:
   - The feature is controlled by build secrets in `.env`:
     - `ACCOUNT_AUTH_CLIENT_ID`: OAuth client id (default `UNSET` hides the feature).
     - `ACCOUNT_AUTH_AUTHORIZE_URL`: Authorization endpoint (`https://auth.openai.com/oauth/authorize`).
     - `ACCOUNT_AUTH_TOKEN_URL`: Token endpoint (`https://auth.openai.com/oauth/token`).
     - `ACCOUNT_AUTH_REDIRECT_URI`: Local redirect callback (`http://127.0.0.1:1455/auth/callback`).
     - `ACCOUNT_AUTH_SCOPES`: Requested OAuth scopes.
     - `ACCOUNT_AUTH_INFERENCE_URL`: Model inference backend (`https://chatgpt.com/backend-api/codex`).
     - `ACCOUNT_AUTH_EXTRA_PARAMS`: Query parameters for auth flow.
     - `ACCOUNT_AUTH_ORIGINATOR`: Originator header value.
     - `ACCOUNT_AUTH_BETA_HEADER`: OpenAI-Beta header (`responses=experimental`).
