/** Header properties used by the rc-compare staging harness. */
const HEADER_MAGIC = 0x048c0000;
const DOC_DENSITY_AT_GENERATION = 7;
const TYPE_FLOAT = 1;

/**
 * Read DOC_DENSITY_AT_GENERATION from an AndroidX modern Remote Compose header.
 *
 * Older documents have no property table, so callers supply the density their lane historically
 * used. Malformed properties also fall back instead of making an optional comparison lane fail.
 */
export function generationDensity(bytes, fallback = 2) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 17) return fallback;
  const encodedMajor = bytes.readInt32BE(1);
  if ((encodedMajor & 0xffff0000) !== HEADER_MAGIC) return fallback;
  const count = bytes.readInt32BE(13);
  if (count < 0) return fallback;

  let offset = 17;
  for (let index = 0; index < count; index++) {
    if (offset + 4 > bytes.length) return fallback;
    const tag = bytes.readUInt16BE(offset);
    const length = bytes.readUInt16BE(offset + 2);
    offset += 4;
    if (offset + length > bytes.length) return fallback;
    const type = tag >>> 10;
    const key = tag & 0x3f;
    if (type === TYPE_FLOAT && key === DOC_DENSITY_AT_GENERATION && length === 4) {
      const density = bytes.readFloatBE(offset);
      return Number.isFinite(density) && density > 0 ? density : fallback;
    }
    offset += length;
  }
  return fallback;
}
