//! AirPlay 2 实时音频的 PCM 输出队列。
//!
//! Rust 侧负责收 RTP、AES-CBC 解密、ALAC 解码；解出的 16-bit PCM 放进这个
//! 环形队列，Java 侧用 `AudioTrack` 轮询取走。这样复用 Android 的音频输出，
//! 不需要把 ALAC 解码器再移植到 Rust/NDK。

use std::collections::VecDeque;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Mutex, OnceLock};

/// 约 1 秒的缓冲（44.1 kHz 立体声的 i16 个数）。
const MAX_SAMPLES: usize = 44_100 * 2;

static QUEUE: OnceLock<Mutex<VecDeque<i16>>> = OnceLock::new();
static SAMPLE_RATE: AtomicU32 = AtomicU32::new(44_100);
static CHANNELS: AtomicU32 = AtomicU32::new(2);

fn queue() -> &'static Mutex<VecDeque<i16>> {
    QUEUE.get_or_init(|| Mutex::new(VecDeque::with_capacity(MAX_SAMPLES)))
}

/// 当前输出格式 `(采样率, 声道数)`。
pub fn format() -> (u32, u32) {
    (
        SAMPLE_RATE.load(Ordering::Relaxed),
        CHANNELS.load(Ordering::Relaxed),
    )
}

/// 切换输出格式；格式变化时清空旧数据。
pub fn set_format(sample_rate: u32, channels: u32) {
    if (SAMPLE_RATE.load(Ordering::Relaxed), CHANNELS.load(Ordering::Relaxed))
        != (sample_rate, channels)
    {
        SAMPLE_RATE.store(sample_rate, Ordering::Relaxed);
        CHANNELS.store(channels, Ordering::Relaxed);
        clear();
    }
}

/// 放入一段 PCM（交错立体声 i16）。队列满时丢掉最旧的数据，控制延迟。
pub fn push_pcm(pcm: &[i16]) {
    let mut q = queue().lock().unwrap();
    if q.len() + pcm.len() > MAX_SAMPLES {
        let drop_n = (q.len() + pcm.len() - MAX_SAMPLES).max(pcm.len() / 2);
        let drop_n = drop_n.min(q.len());
        q.drain(..drop_n);
    }
    q.extend(pcm.iter().copied());
}

/// 取走最多 `out.len()` 个 i16，返回实际数量。
pub fn take(out: &mut [i16]) -> usize {
    let mut q = queue().lock().unwrap();
    let n = out.len().min(q.len());
    for slot in out.iter_mut().take(n) {
        *slot = q.pop_front().unwrap();
    }
    n
}

pub fn clear() {
    queue().lock().unwrap().clear();
}

pub fn pending() -> usize {
    queue().lock().unwrap().len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_take_and_clear() {
        clear();
        push_pcm(&[1, 2, 3, 4]);
        let mut out = [0i16; 2];
        assert_eq!(take(&mut out), 2);
        assert_eq!(out, [1, 2]);
        let mut out = [0i16; 8];
        assert_eq!(take(&mut out), 2);
        assert_eq!(&out[..2], [3, 4]);
        assert_eq!(take(&mut out), 0);
    }

    #[test]
    fn overflow_drops_oldest() {
        clear();
        push_pcm(&vec![7i16; MAX_SAMPLES]);
        push_pcm(&[9i16; 1024]);
        let mut out = vec![0i16; MAX_SAMPLES];
        let n = take(&mut out);
        assert!(n <= MAX_SAMPLES);
        assert_eq!(out[n - 1024], 9);
    }
}
