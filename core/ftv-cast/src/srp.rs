//! SRP-6a（3072-bit, SHA-512）：HAP 的 pair-setup 用这套。
//! N/g 取自 RFC 5054 的 3072 位组（与开源实现一致）。

use num_bigint::BigUint;
use num_traits::One;
use sha2::{Digest, Sha512};

const N_HEX: &str = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74\n020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437\n4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED\nEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05\n98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB\n9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B\nE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718\n3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33\nA85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7\nABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864\nD87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2\n08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF";

fn n() -> BigUint {
    let clean: String = N_HEX.chars().filter(|c| c.is_ascii_hexdigit()).collect();
    BigUint::parse_bytes(clean.as_bytes(), 16).expect("N")
}

fn h(bytes: &[u8]) -> Vec<u8> {
    let mut d = Sha512::new();
    d.update(bytes);
    d.finalize().to_vec()
}

fn pad(x: &BigUint, len: usize) -> Vec<u8> {
    let mut bytes = x.to_bytes_be();
    if bytes.len() >= len {
        return bytes[bytes.len() - len..].to_vec();
    }
    let mut out = vec![0u8; len - bytes.len()];
    out.append(&mut bytes);
    out
}

fn from_bytes(b: &[u8]) -> BigUint {
    BigUint::from_bytes_be(b)
}

/// 服务端会话。
pub struct SrpServer {
    n: BigUint,
    g: BigUint,
    salt: Vec<u8>,
    verifier: BigUint,
    b: BigUint,
    b_pub: BigUint,
    pub_public: [u8; 384],
}

impl SrpServer {
    /// 用密码（transient 配对时随机生成）建立会话，返回 (服务端, salt, B)。
    pub fn new(password: &[u8], salt: Vec<u8>, b: BigUint) -> Self {
        let n = n();
        let g = BigUint::from(5u32);
        let pad_len = 384;
        // k = H(N | PAD(g))
        let mut kn = pad(&n, pad_len);
        kn.extend_from_slice(&pad(&g, pad_len));
        let k = from_bytes(&h(&kn));
        // x = H(s | H("Pair-Setup" | ":" | P))
        let mut up = b"Pair-Setup:".to_vec();
        up.extend_from_slice(password);
        let inner = h(&up);
        let mut xs = salt.clone();
        xs.extend_from_slice(&inner);
        let x = from_bytes(&h(&xs));
        let v = g.modpow(&x, &n);
        let b_pub = (&k * &v + g.modpow(&b, &n)) % &n;
        let public = pad(&b_pub, pad_len);
        let mut arr = [0u8; 384];
        arr.copy_from_slice(&public);
        Self { n, g, salt, verifier: v, b, b_pub, pub_public: arr }
    }

    pub fn salt(&self) -> &[u8] {
        &self.salt
    }

    pub fn public_key(&self) -> &[u8; 384] {
        &self.pub_public
    }

    /// 收到客户端 A 后算出 K 与期望的 M1。
    pub fn shared(&self, a_bytes: &[u8]) -> Option<(Vec<u8>, Vec<u8>)> {
        let pad_len = 384;
        let a = from_bytes(a_bytes);
        if a == BigUint::from(0u32) || (&a % &self.n) == BigUint::from(0u32) {
            return None;
        }
        // u = H(PAD(A) | PAD(B))
        let mut ub = pad(&a, pad_len);
        ub.extend_from_slice(&pad(&self.b_pub, pad_len));
        let u = from_bytes(&h(&ub));
        // S = (A * v^u)^b mod N
        let s = (&a * self.verifier.modpow(&u, &self.n)).modpow(&self.b, &self.n);
        let k = h(&s.to_bytes_be());
        // M1 = H(H(N) xor H(g) | H(I) | s | A | B | K)
        let hn = h(&pad(&self.n, pad_len));
        let hg = h(&pad(&self.g, pad_len));
        let mut x = vec![0u8; hn.len()];
        for i in 0..hn.len() {
            x[i] = hn[i] ^ hg[i];
        }
        x.extend_from_slice(&h(b"Pair-Setup"));
        x.extend_from_slice(&self.salt);
        x.extend_from_slice(&pad(&a, pad_len));
        x.extend_from_slice(&pad(&self.b_pub, pad_len));
        x.extend_from_slice(&k);
        let m1 = h(&x);
        Some((k, m1))
    }

    /// 校验客户端 M1 并返回服务端 M2 = H(A | M1 | K)。
    pub fn verify(&self, a_bytes: &[u8], client_m1: &[u8]) -> Option<Vec<u8>> {
        let (k, m1) = self.shared(a_bytes)?;
        if m1.as_slice() != client_m1 {
            return None;
        }
        let mut x = pad(&from_bytes(a_bytes), 384);
        x.extend_from_slice(&m1);
        x.extend_from_slice(&k);
        Some(h(&x))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 用同一密码模拟客户端，验证双方 K / M1 / M2 一致。
    #[test]
    fn srp_roundtrip() {
        let password = b"1234";
        let salt = vec![9u8; 16];
        let server = SrpServer::new(password, salt.clone(), BigUint::from(7u32));
        let a = BigUint::from(123456789u64);
        let a_pub = BigUint::from(5u32).modpow(&a, &n());
        let a_bytes = pad(&a_pub, 384);

        // 客户端计算 x、v、k、S、K、M1
        let mut up = b"Pair-Setup:".to_vec();
        up.extend_from_slice(password);
        let inner = h(&up);
        let mut xs = salt.clone();
        xs.extend_from_slice(&inner);
        let x = from_bytes(&h(&xs));
        let v = BigUint::from(5u32).modpow(&x, &n());
        let mut kn = pad(&n(), 384);
        kn.extend_from_slice(&pad(&BigUint::from(5u32), 384));
        let k = from_bytes(&h(&kn));
        let b_pub = from_bytes(server.public_key());
        let mut ub = a_bytes.clone();
        ub.extend_from_slice(&pad(&b_pub, 384));
        let u = from_bytes(&h(&ub));
        let s = (&b_pub - &k * &v % &n()).modpow(&(a + u * x), &n());
        let client_k = h(&s.to_bytes_be());

        let (server_k, server_m1) = server.shared(&a_bytes).unwrap();
        assert_eq!(client_k, server_k, "共享密钥应一致");

        // 客户端 M1
        let hn = h(&pad(&n(), 384));
        let hg = h(&pad(&BigUint::from(5u32), 384));
        let mut mx = vec![0u8; hn.len()];
        for i in 0..hn.len() {
            mx[i] = hn[i] ^ hg[i];
        }
        mx.extend_from_slice(&h(b"Pair-Setup"));
        mx.extend_from_slice(&salt);
        mx.extend_from_slice(&a_bytes);
        mx.extend_from_slice(&pad(&b_pub, 384));
        mx.extend_from_slice(&client_k);
        let client_m1 = h(&mx);
        assert_eq!(client_m1, server_m1, "M1 应一致");

        let m2 = server.verify(&a_bytes, &client_m1).expect("M2");
        let mut x2 = a_bytes.clone();
        x2.extend_from_slice(&client_m1);
        x2.extend_from_slice(&client_k);
        assert_eq!(m2, h(&x2), "M2 应一致");
    }

    #[test]
    fn rejects_wrong_proof() {
        let server = SrpServer::new(b"1234", vec![1u8; 16], BigUint::from(3u32));
        let a = pad(&BigUint::from(5u32).modpow(&BigUint::from(9u32), &n()), 384);
        assert!(server.verify(&a, &[0u8; 64]).is_none());
    }
}
