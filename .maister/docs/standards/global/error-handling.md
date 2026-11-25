## Error Handling

### Clear User Messages

Show helpful, actionable messages without exposing internal details or security-sensitive information.

### Fail Fast

Validate inputs and check preconditions early; reject invalid data before it causes deeper issues.

### Typed Exceptions

Use specific exception types instead of generic ones to enable precise error handling.

### Centralized Handling

Catch and process errors at appropriate boundaries (controllers, API layers) rather than scattering try-catch
throughout.

### Graceful Degradation

When non-critical services fail, continue operating with reduced functionality rather than crashing entirely.

### Retry with Backoff

Use exponential backoff for transient failures when calling external services.

### Resource Cleanup

Always release resources (file handles, connections) in finally blocks or equivalent cleanup mechanisms.

### Project application

In YapYap, failures crossing service/layer boundaries are two-valued typed verdicts (PERMANENT/DEFER) rendered as
outcomes, never retryable exceptions — see protocol/delivery.md. Lower-layer exceptions are translated into the
receiving layer's sealed vocabulary at the boundary — see architecture/dependencies.md.
