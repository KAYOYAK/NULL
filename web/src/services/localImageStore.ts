/**
 * Local image hand-off.
 *
 * In the cloud build an attachment is uploaded and referenced by a `file_id`.
 * There is no server here, so the bytes stay in memory and the bridge takes them
 * at send time. The lifetime is deliberate: a page reload drops them, which is
 * the same thing that happens to the cloud uploads' server copies when a session
 * is deleted -- nothing is written to disk that the conversation did not keep.
 *
 * The map is keyed by the id the attachment layer hands to the request builder,
 * prefixed so a cloud file_id can never collide with a local one.
 */
const images = new Map<string, Blob>()

const PREFIX = 'local:'

export const isLocalImageId = (id: string): boolean => id.startsWith(PREFIX)

export const registerLocalImage = (id: string, blob: Blob): string => {
  const key = PREFIX + id
  images.set(key, blob)
  return key
}

export const hasLocalImage = (id: string): boolean => images.has(id)

const toBase64 = (bytes: Uint8Array): string => {
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk))
  }
  return btoa(binary)
}

/** The image as base64, or null when the id is unknown (page reloaded since). */
export const readLocalImage = async (id: string): Promise<string | null> => {
  const blob = images.get(id)
  if (!blob) {
    return null
  }
  return toBase64(new Uint8Array(await blob.arrayBuffer()))
}

export const forgetLocalImage = (id: string) => {
  images.delete(id)
}
