extern "C" {
#include <stdint.h>
}

// ====== Константы secp256k1 (параметры кривой) ======
// TODO: заполните p, n, Gx, Gy в представлении для ядра (массивы 32 байта)

// ====== Полевые операции mod p (device) ======
// TODO: addModP, subModP, mulModP, invModP, normalize, и т.д.

// ====== Точки в координатах Якоби ======
struct PointJ { uint32_t X[8]; uint32_t Y[8]; uint32_t Z[8]; }; // 8*32=256 бит

__device__ void pointDoubleJ(PointJ &R, const PointJ &P) {
    // TODO: удвоение в Якоби
}

__device__ void pointAddJ(PointJ &R, const PointJ &P, const PointJ &Q) {
    // TODO: сложение в Якоби (обрабатывайте особые случаи Z=0)
}

__device__ void mulBase(PointJ &R, const uint32_t k[8]) {
    // TODO: умножение на базовую точку G (можно WNAF/предтаблицы)
}

__device__ void addBaseStride(PointJ &R, const PointJ &Gstride) {
    // R = R + G_stride
    PointJ T; pointAddJ(T, R, Gstride); R = T;
}

__device__ void jacobianToAffine(uint32_t x[8], uint32_t y[8], const PointJ &P) {
    // TODO: инверсия Z, перевод в аффинные, нормализация
}

// ====== Keccak-256 (минимальная реализация) ======
// Небольшая, но полная реализация Keccak-f[1600] для 32-байтного дайджеста
// (для ETH: keccak(uncompressed_pubkey[1..64]) → 20 байт младших)

#define ROL64(a, offset) (((a) << (offset)) ^ ((a) >> (64-(offset))))

__device__ void keccakF1600(uint64_t s[25]) {
    const uint64_t RC[24] = {
        0x0000000000000001ULL, 0x0000000000008082ULL, 0x800000000000808aULL, 0x8000000080008000ULL,
        0x000000000000808bULL, 0x0000000080000001ULL, 0x8000000080008081ULL, 0x8000000000008009ULL,
        0x000000000000008aULL, 0x0000000000000088ULL, 0x0000000080008009ULL, 0x000000008000000aULL,
        0x000000008000808bULL, 0x800000000000008bULL, 0x8000000000008089ULL, 0x8000000000008003ULL,
        0x8000000000008002ULL, 0x8000000000000080ULL, 0x000000000000800aULL, 0x800000008000000aULL,
        0x8000000080008081ULL, 0x8000000000008080ULL, 0x0000000080000001ULL, 0x8000000080008008ULL
    };
    const int r[25] = { 0,  1, 62, 28, 27, 36, 44,  6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21,  8, 18,  2, 61, 56, 14 };
    const int pi[25] = { 0, 6,12,18,24, 3, 9,10,16,22, 1, 7,13,19,20, 4, 5,11,17,23, 2, 8,14,15,21 };

    for (int round=0; round<24; ++round) {
        uint64_t C[5], D[5];
        for (int x=0; x<5; ++x) C[x] = s[x]^s[x+5]^s[x+10]^s[x+15]^s[x+20];
        for (int x=0; x<5; ++x) D[x] = C[(x+4)%5] ^ ROL64(C[(x+1)%5], 1);
        for (int i=0; i<25; i+=5) for (int x=0; x<5; ++x) s[i+x] ^= D[x];
        uint64_t B[25];
        for (int i=0; i<25; ++i) B[pi[i]] = ROL64(s[i], r[i]);
        for (int i=0; i<25; i+=5) {
            uint64_t b0=B[i+0],b1=B[i+1],b2=B[i+2],b3=B[i+3],b4=B[i+4];
            s[i+0] = b0 ^ ((~b1) & b2);
            s[i+1] = b1 ^ ((~b2) & b3);
            s[i+2] = b2 ^ ((~b3) & b4);
            s[i+3] = b3 ^ ((~b4) & b0);
            s[i+4] = b4 ^ ((~b0) & b1);
        }
        s[0] ^= RC[round];
    }
}

__device__ void keccak256(const uint8_t *in, size_t inLen, uint8_t out[32]) {
    uint64_t s[25];
    #pragma unroll
    for (int i=0;i<25;++i) s[i]=0;
    // Absorb (rate=136)
    size_t rate=136; size_t off=0;
    while (inLen >= rate) {
        #pragma unroll
        for (int i=0;i<rate/8;++i) {
            uint64_t v=0; memcpy(&v, in+off+8*i, 8); s[i] ^= v;
        }
        keccakF1600(s); off+=rate; inLen-=rate;
    }
    uint8_t block[136];
    for (int i=0;i<136;++i) block[i]=0; // pad
    for (int i=0;i<inLen;++i) block[i]=in[off+i];
    block[inLen]=0x01; block[135]|=0x80; // pad10*1
    for (int i=0;i<rate/8;++i) {
        uint64_t v=0; memcpy(&v, block+8*i, 8); s[i] ^= v;
    }
    keccakF1600(s);
    // Squeeze 32 bytes
    memcpy(out, s, 32);
}

// ====== ETH vanity kernel ======
// Входы: k0[], stride, iterations, G_stride (как PointJ), шаблон (префикс по 20 байт адреса),
// Выходы: буфер хитов (k, addr20) и счётчик hitsCount

extern "C" __global__ void eth_vanity_kernel(
    const uint32_t *k0_be,   // 8 слов по 32 бита (big-endian) - стартовый скаляр
    const uint32_t *stride_be,// 8 слов (big-endian) - шаг по скалярам
    const uint64_t iterations,
    const PointJ   *Gstride, // предвычисленное stride*G
    const uint8_t  *pattern, // до 20 байт префикса (ETH addr без 0x)
    const int       patternLen,
    uint8_t        *hits_out,// [maxHits][32(priv)+20(addr)]
    int            *hitsCount,
    const int       maxHits
) {
    // 1) Вычислить стартовый k для потока: k = k0 + tid*stride
    // TODO: big-int add mod n
    uint32_t k[8]; // = k0 + tid*stride (mod n)

    // 2) Q = k * G
    PointJ Q; mulBase(Q, k);

    // 3) Цикл итераций
    for (uint64_t i=0; i<iterations; ++i) {
        // 3.1) Affine pubkey
        uint32_t x[8], y[8]; jacobianToAffine(x,y,Q);
        // Сформировать 64-байтный uncompressed pubkey без 0x04 (x||y)
        uint8_t pub[64]; // TODO: store big-endian x,y
        // 3.2) keccak(pub)
        uint8_t h[32]; keccak256(pub, 64, h);
        // 3.3) взять последние 20 байт (ETH адрес)
        const uint8_t *addr20 = h + 12;
        // 3.4) матчинг префикса (по байтам, не по hex-строке)
        bool ok=true;
        for (int j=0; j<patternLen; ++j) { if (addr20[j]!=pattern[j]) { ok=false; break; } }
        if (ok) {
            int slot = atomicAdd(hitsCount, 1);
            if (slot < maxHits) {
                // Записать приватник (32 байта) и адрес (20)
                // TODO: сериализуйте k в big-endian
                uint8_t *dst = hits_out + slot*(32+20);
                // write priv32
                // write addr20
            }
        }
        // 3.5) Q += G_stride; k += stride
        addBaseStride(Q, *Gstride);
        // TODO: k = k + stride (mod n)
    }
}