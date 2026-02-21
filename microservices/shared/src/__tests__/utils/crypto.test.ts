import { encrypt, decrypt, generateSecureToken, hashString } from '../../utils/crypto';

describe('encrypt / decrypt', () => {
  const key = 'my-secret-key-32-chars-long-here';
  const plaintext = 'sensitive data';

  it('should encrypt and decrypt text correctly', () => {
    const encrypted = encrypt(plaintext, key);
    const decrypted = decrypt(encrypted, key);
    expect(decrypted).toBe(plaintext);
  });

  it('should produce different ciphertext each time (random IV)', () => {
    const enc1 = encrypt(plaintext, key);
    const enc2 = encrypt(plaintext, key);
    expect(enc1).not.toBe(enc2);
  });

  it('should throw on tampered ciphertext', () => {
    const encrypted = encrypt(plaintext, key);
    const tampered = encrypted.replace(/.$/, 'X');
    expect(() => decrypt(tampered, key)).toThrow();
  });

  it('should throw on invalid format', () => {
    expect(() => decrypt('invalid', key)).toThrow('Invalid encrypted text format');
  });
});

describe('generateSecureToken', () => {
  it('should generate a token of correct length', () => {
    const token = generateSecureToken(32);
    expect(token).toHaveLength(64); // hex encoding doubles length
  });

  it('should generate unique tokens', () => {
    const t1 = generateSecureToken();
    const t2 = generateSecureToken();
    expect(t1).not.toBe(t2);
  });
});

describe('hashString', () => {
  it('should produce consistent hash', () => {
    const hash1 = hashString('hello');
    const hash2 = hashString('hello');
    expect(hash1).toBe(hash2);
  });

  it('should produce different hashes for different inputs', () => {
    expect(hashString('hello')).not.toBe(hashString('world'));
  });
});
