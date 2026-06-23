package agents_engine.ap2

/**
 * `agents_engine/ap2/Ap2MandateException.kt` — AP2 (PRD §12.10) spike. A presented mandate VC was structurally
 * malformed (missing/typed-wrong `credentialSubject` field). Distinct from a *crypto* failure
 * (`BadgeVerificationException` from the identity verifier) and from a *policy* refusal
 * ([Ap2PaymentDeniedException]) — this means "the signature was fine but the mandate isn't well-formed."
 */
class Ap2MandateException(message: String) : RuntimeException(message)
