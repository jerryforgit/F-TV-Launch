//! HAP 配对状态机：`pair-setup`（SRP）与 `pair-verify`（X25519 + Ed25519）。
//! 密码由调用方决定（AirPlay 2 的 transient 配对策略在 `AirPlayConfig` 里配置）。

use crate::crypto;
use crate::srp::SrpServer;
use crate::tlv8::{self, *};
use num_bigint::BigUint;

pub const PAIR_SETUP: u8 = 0;      // kTLVType_Method = Pair Setup
pub const PAIR_VERIFY: u8 = 2;

fn state(items: &[(u8, Vec<u8>)]) -> Option<u8> {
    tlv8::get(items, STATE).and_then(|v| v.first().copied())
}

/// pair-setup（M1–M4）：成功后返回客户端证明已校验。
pub struct PairSetup {
    srp: Option<SrpServer>,
    salt: Vec<u8>,
    password: Vec<u8>,
    done: bool,
}

impl PairSetup {
    pub fn new(password: &[u8]) -> Self {
        Self {
            srp: None,
            salt: Vec::new(),
            password: password.to_vec(),
            done: false,
        }
    }

    /// 处理一段 TLV8 请求，返回响应 TLV8。
    pub fn handle(&mut self, request: &[u8]) -> Vec<u8> {
        let items = tlv8::decode(request);
        match state(&items) {
            Some(1) => {
                self.salt = (0..16).map(|i| (i as u8).wrapping_mul(37).wrapping_add(11)).collect();
                let b = BigUint::from(0x1234_5678_9abc_def0u64);
                let srp = SrpServer::new(&self.password, self.salt.clone(), b);
                let public = srp.public_key().to_vec();
                self.srp = Some(srp);
                let mut flags = vec![];
                if let Some(f) = tlv8::get(&items, FLAGS) {
                    flags.extend_from_slice(f);
                }
                let mut out = tlv8::encode(&[(STATE, &[2]), (PUBLIC_KEY, &public), (SALT, &self.salt)]);
                if !flags.is_empty() {
                    out.extend_from_slice(&tlv8::encode(&[(FLAGS, &flags)]));
                }
                out
            }
            Some(3) => {
                let a = tlv8::get(&items, PUBLIC_KEY).unwrap_or(&[]);
                let proof = tlv8::get(&items, PROOF).unwrap_or(&[]);
                match self.srp.as_ref().and_then(|s| s.verify(a, proof)) {
                    Some(m2) => {
                        self.done = true;
                        tlv8::encode(&[(STATE, &[4]), (PROOF, &m2)])
                    }
                    None => tlv8::encode(&[(STATE, &[4]), (ERROR, &[0x02])]),
                }
            }
            _ => tlv8::encode(&[(STATE, &[2]), (ERROR, &[0x01])]),
        }
    }

    pub fn is_done(&self) -> bool {
        self.done
    }
}

pub const SALT: u8 = 0x02;

/// pair-verify：拿到客户端公钥后回服务端公钥 + 加密的签名。
pub struct PairVerify {
    client_ltpk: Option<[u8; 32]>,
    client_pub: Option<[u8; 32]>,
    secret: Option<[u8; 32]>,
    server_pub: [u8; 32],
    signing: ed25519_dalek::SigningKey,
    device_id: Vec<u8>,
    session_key: Option<[u8; 32]>,
}

impl PairVerify {
    /// 用指定的长期身份密钥创建（保证 /info 里的 pk 与配对一致）。
    pub fn with_key(device_id: &[u8], sk: ed25519_dalek::SigningKey) -> Self {
        Self {
            client_ltpk: None,
            client_pub: None,
            secret: None,
            server_pub: [0; 32],
            signing: sk,
            device_id: device_id.to_vec(),
            session_key: None,
        }
    }

    pub fn new(device_id: &[u8]) -> Self {
        let (sk, _pk) = crypto::ed25519_keypair();
        Self {
            client_ltpk: None,
            client_pub: None,
            secret: None,
            server_pub: [0; 32],
            signing: sk,
            device_id: device_id.to_vec(),
            session_key: None,
        }
    }

    fn derive(shared: &[u8; 32], info: &[u8]) -> [u8; 32] {
        let mut out = [0u8; 32];
        crypto::hkdf_sha512(shared, shared, info, &mut out);
        out
    }

    pub fn handle(&mut self, request: &[u8]) -> Vec<u8> {
        let items = tlv8::decode(request);
        match state(&items) {
            Some(1) => {
                let client_pub: [u8; 32] = match tlv8::get(&items, PUBLIC_KEY) {
                    Some(v) if v.len() == 32 => v.try_into().unwrap(),
                    _ => return tlv8::encode(&[(STATE, &[2]), (ERROR, &[0x01])]),
                };
                let (secret, server_pub) = crypto::x25519_keypair();
                let shared = crypto::x25519_shared(&secret, &client_pub);
                self.secret = Some(shared);
                self.server_pub = server_pub;
                self.client_pub = Some(client_pub);

                let key = Self::derive(&shared, b"Pair-Verify-Encrypt-Salt");
                let mut msg = client_pub.to_vec();
                msg.extend_from_slice(&server_pub);
                let sig = crypto::ed25519_sign(&self.signing, &msg);
                let inner = tlv8::encode(&[
                    (IDENTIFIER, &self.device_id),
                    (SIGNATURE, &sig),
                ]);
                let nonce = *b"PV-Msg03\0\0\0\0";
                let encrypted = crypto::seal(&key, &nonce, &inner, b"");
                let mut out = tlv8::encode(&[(STATE, &[2]), (PUBLIC_KEY, &server_pub)]);
                out.extend_from_slice(&tlv8::encode(&[(ENCRYPTED_DATA, &encrypted)]));
                out
            }
            Some(3) => {
                let client_pub = self.client_pub.unwrap_or([0u8; 32]);
                let shared = match self.secret {
                    Some(s) => s,
                    None => return tlv8::encode(&[(STATE, &[4]), (ERROR, &[0x01])]),
                };
                let key = Self::derive(&shared, b"Pair-Verify-Encrypt-Salt");
                let nonce = *b"PV-Msg03\0\0\0\0";
                let encrypted = tlv8::get(&items, ENCRYPTED_DATA).unwrap_or(&[]);
                let plain = match crypto::open(&key, &nonce, encrypted, b"") {
                    Some(p) => p,
                    None => return tlv8::encode(&[(STATE, &[4]), (ERROR, &[0x02])]),
                };
                let inner = tlv8::decode(&plain);
                let signature = tlv8::get(&inner, SIGNATURE).unwrap_or(&[]);
                let identifier = tlv8::get(&inner, IDENTIFIER).unwrap_or(&[]);
                // 客户端长期公钥：优先用 M3 里带的，其次用 pair-setup 阶段保存的
                let ltpk: [u8; 32] = match tlv8::get(&inner, PUBLIC_KEY) {
                    Some(v) if v.len() == 32 => v.try_into().unwrap(),
                    _ => match self.client_ltpk {
                        Some(k) => k,
                        None => return tlv8::encode(&[(STATE, &[4]), (ERROR, &[0x02])]),
                    },
                };
                // 签名覆盖：clientPublic || identifier || serverPublic
                let mut msg = client_pub.to_vec();
                msg.extend_from_slice(identifier);
                msg.extend_from_slice(&self.server_pub);
                let sig: [u8; 64] = match signature.try_into() {
                    Ok(s) => s,
                    Err(_) => return tlv8::encode(&[(STATE, &[4]), (ERROR, &[0x02])]),
                };
                if crypto::ed25519_verify(&ltpk, &msg, &sig) {
                    self.client_ltpk = Some(ltpk);
                    self.session_key = Some(key);
                    tlv8::encode(&[(STATE, &[4])])
                } else {
                    tlv8::encode(&[(STATE, &[4]), (ERROR, &[0x02])])
                }
            }
            _ => tlv8::encode(&[(STATE, &[2]), (ERROR, &[0x01])]),
        }
    }

    pub fn session_key(&self) -> Option<[u8; 32]> {
        self.session_key
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pair_setup_rejects_bad_m1() {
        let mut p = PairSetup::new(b"1234");
        let out = p.handle(&tlv8::encode(&[(STATE, &[9])]));
        let items = tlv8::decode(&out);
        assert_eq!(tlv8::get(&items, STATE), Some(&[2u8][..]));
        assert!(tlv8::get(&items, ERROR).is_some());
    }

    #[test]
    fn pair_setup_m1_returns_salt_and_key() {
        let mut p = PairSetup::new(b"1234");
        let req = tlv8::encode(&[(STATE, &[1]), (METHOD, &[PAIR_SETUP]), (FLAGS, &[0x10])]);
        let out = p.handle(&req);
        let items = tlv8::decode(&out);
        assert_eq!(tlv8::get(&items, STATE), Some(&[2u8][..]));
        assert_eq!(tlv8::get(&items, SALT).unwrap().len(), 16);
        assert_eq!(tlv8::get(&items, PUBLIC_KEY).unwrap().len(), 384);
    }

    #[test]
    fn pair_verify_m3_checks_signature() {
        let mut v = PairVerify::new(b"FTV");
        let (client_sk, client_pk) = crypto::ed25519_keypair();
        let (client_x_sk, client_x_pk) = crypto::x25519_keypair();

        let m1 = tlv8::encode(&[(STATE, &[1]), (PUBLIC_KEY, &client_x_pk)]);
        let out = v.handle(&m1);
        let items = tlv8::decode(&out);
        let server_pub: [u8; 32] =
            tlv8::get(&items, PUBLIC_KEY).unwrap().try_into().unwrap();

        let shared = crypto::x25519_shared(&client_x_sk, &server_pub);
        let mut key = [0u8; 32];
        crypto::hkdf_sha512(&shared, &shared, b"Pair-Verify-Encrypt-Salt", &mut key);

        let identifier = b"ios-device";
        let mut msg = client_x_pk.to_vec();
        msg.extend_from_slice(identifier);
        msg.extend_from_slice(&server_pub);
        let sig = crypto::ed25519_sign(&client_sk, &msg);
        let inner = tlv8::encode(&[
            (IDENTIFIER, identifier),
            (SIGNATURE, &sig),
            (PUBLIC_KEY, &client_pk),
        ]);
        let encrypted = crypto::seal(&key, b"PV-Msg03\0\0\0\0", &inner, b"");
        let m3 = tlv8::encode(&[(STATE, &[3]), (ENCRYPTED_DATA, &encrypted)]);
        let ok = tlv8::decode(&v.handle(&m3));
        assert_eq!(tlv8::get(&ok, STATE), Some(&[4u8][..]));
        assert!(tlv8::get(&ok, ERROR).is_none(), "合法签名应通过");
        assert!(v.session_key().is_some());

        // 篡改签名应被拒
        let mut bad_sig = sig;
        bad_sig[0] ^= 0xff;
        let inner_bad = tlv8::encode(&[
            (IDENTIFIER, identifier),
            (SIGNATURE, &bad_sig),
            (PUBLIC_KEY, &client_pk),
        ]);
        let mut v2 = PairVerify::new(b"FTV");
        v2.handle(&m1);
        let enc_bad = crypto::seal(&key, b"PV-Msg03\0\0\0\0", &inner_bad, b"");
        let m3_bad = tlv8::encode(&[(STATE, &[3]), (ENCRYPTED_DATA, &enc_bad)]);
        let bad = tlv8::decode(&v2.handle(&m3_bad));
        assert!(tlv8::get(&bad, ERROR).is_some(), "篡改签名应被拒");
    }

    #[test]
    fn pair_verify_m1_returns_encrypted_signature() {
        let mut v = PairVerify::new(b"FTV");
        let (_sk, pubkey) = crypto::x25519_keypair();
        let req = tlv8::encode(&[(STATE, &[1]), (PUBLIC_KEY, &pubkey)]);
        let out = v.handle(&req);
        let items = tlv8::decode(&out);
        assert_eq!(tlv8::get(&items, STATE), Some(&[2u8][..]));
        assert_eq!(tlv8::get(&items, PUBLIC_KEY).unwrap().len(), 32);
        assert!(tlv8::get(&items, ENCRYPTED_DATA).unwrap().len() > 64);
    }
}
