//! HAP 用到的密码学原语：X25519、Ed25519、HKDF-SHA512、ChaCha20-Poly1305。

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use hkdf::Hkdf;
use sha2::Sha512;
use x25519_dalek::{PublicKey, StaticSecret};

/// 生成一对 X25519 密钥，返回（私钥, 公钥字节）。
pub fn x25519_keypair() -> (StaticSecret, [u8; 32]) {
    let secret = StaticSecret::random_from_rng(rand_core::OsRng);
    let public = PublicKey::from(&secret);
    (secret, public.to_bytes())
}

/// 与对方公钥算共享密钥。
pub fn x25519_shared(secret: &StaticSecret, peer: &[u8; 32]) -> [u8; 32] {
    secret.diffie_hellman(&PublicKey::from(*peer)).to_bytes()
}

/// HKDF-SHA512。
pub fn hkdf_sha512(salt: &[u8], ikm: &[u8], info: &[u8], out: &mut [u8]) {
    let hk = Hkdf::<Sha512>::new(Some(salt), ikm);
    hk.expand(info, out).expect("hkdf length");
}

/// ChaCha20-Poly1305 加密（HAP 的 nonce 是 12 字节）。
pub fn seal(key: &[u8; 32], nonce: &[u8; 12], plaintext: &[u8], aad: &[u8]) -> Vec<u8> {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
    cipher
        .encrypt(Nonce::from_slice(nonce), Payload { msg: plaintext, aad })
        .expect("seal")
}

/// ChaCha20-Poly1305 解密。
pub fn open(key: &[u8; 32], nonce: &[u8; 12], ciphertext: &[u8], aad: &[u8]) -> Option<Vec<u8>> {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
    cipher
        .decrypt(Nonce::from_slice(nonce), Payload { msg: ciphertext, aad })
        .ok()
}

pub fn ed25519_keypair() -> (ed25519_dalek::SigningKey, [u8; 32]) {
    let sk = ed25519_dalek::SigningKey::generate(&mut rand_core::OsRng);
    let pk = sk.verifying_key().to_bytes();
    (sk, pk)
}

pub fn ed25519_sign(sk: &ed25519_dalek::SigningKey, msg: &[u8]) -> [u8; 64] {
    use ed25519_dalek::Signer;
    sk.sign(msg).to_bytes()
}

pub fn ed25519_verify(pk: &[u8; 32], msg: &[u8], sig: &[u8; 64]) -> bool {
    use ed25519_dalek::Verifier;
    match ed25519_dalek::VerifyingKey::from_bytes(pk) {
        Ok(key) => match ed25519_dalek::Signature::from_slice(sig) {
            Ok(s) => key.verify(msg, &s).is_ok(),
            Err(_) => false,
        },
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn x25519_agree() {
        let (a_sk, a_pk) = x25519_keypair();
        let (b_sk, b_pk) = x25519_keypair();
        assert_eq!(x25519_shared(&a_sk, &b_pk), x25519_shared(&b_sk, &a_pk));
    }

    #[test]
    fn hkdf_deterministic() {
        let mut a = [0u8; 32];
        let mut b = [0u8; 32];
        hkdf_sha512(b"salt", b"ikm", b"info", &mut a);
        hkdf_sha512(b"salt", b"ikm", b"info", &mut b);
        assert_eq!(a, b);
    }

    #[test]
    fn chacha_roundtrip_and_tamper() {
        let key = [7u8; 32];
        let nonce = [1u8; 12];
        let ct = seal(&key, &nonce, b"hello airplay", b"aad");
        assert_eq!(open(&key, &nonce, &ct, b"aad").unwrap(), b"hello airplay");
        assert!(open(&key, &nonce, &ct, b"other").is_none());
    }

    #[test]
    fn ed25519_sign_verify() {
        let (sk, pk) = ed25519_keypair();
        let sig = ed25519_sign(&sk, b"pair-verify");
        assert!(ed25519_verify(&pk, b"pair-verify", &sig));
        assert!(!ed25519_verify(&pk, b"tampered", &sig));
    }
}
