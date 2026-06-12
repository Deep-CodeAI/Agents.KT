package agents_engine.model

import agents_engine.content.Content

/**
 * #3867.b — typed image generation: prompt in, [Content.Image] out (the
 * bytes land in the caller's `BlobStore`, the typed ref travels). Ship:
 * [OpenAiImagesClient] (Images API). `Agent<Brief, Content.Image>` is a
 * coherent agent shape with this on a deterministic skill.
 */
fun interface ImageModelClient {
    fun generate(prompt: String): Content.Image
}
