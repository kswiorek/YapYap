package org.yapyap.protection

sealed class ProtectionException(message: String) : Exception(message) {
    class DecodeError : ProtectionException("Envelope decode failed")
    class SignatureVerificationFailed : ProtectionException("Signature verification failed")
    class SignatureMissing : ProtectionException("Signature missing")
}