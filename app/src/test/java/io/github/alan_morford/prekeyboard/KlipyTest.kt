package io.github.alan_morford.prekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** KLIPY's answers read, and the file chosen to send for what a field takes. */
@RunWith(RobolectricTestRunner::class)
class KlipyTest {

    private val json = """
        {"result":true,"data":{"data":[
          {"id":8041071659142944,"slug":"hello-hi-662","title":"Hello","type":"gif",
           "blur_preview":"data:image/jpeg;base64,AAAA",
           "file":{
             "hd":{"gif":{"url":"https://static.klipy.com/hd.gif","width":498,"height":498,"size":4001918}},
             "md":{"gif":{"url":"https://static.klipy.com/md.gif","width":320,"height":320,"size":2001918},
                   "webp":{"url":"https://static.klipy.com/md.webp","width":320,"height":320,"size":200000}},
             "sm":{"gif":{"url":"https://static.klipy.com/sm.gif","width":220,"height":110,"size":300000},
                   "jpg":{"url":"https://static.klipy.com/sm.jpg","width":220,"height":110,"size":7000}}}},
          {"type":"ad","content":"<div></div>"},
          {"id":2,"slug":"grrr","title":"Grrr Sticker","type":"sticker",
           "file":{"md":{"png":{"url":"https://static.klipy.com/g.png","width":189,"height":200,"size":3178},
                         "webp":{"url":"https://static.klipy.com/g.webp","width":189,"height":200,"size":16260}}}}
        ],"current_page":1,"per_page":24,"has_next":true}}
    """.trimIndent()

    @Test fun readsAPage() {
        val p = Klipy.parse(json)
        assertEquals(2, p.items.size)                     // the ad, with no file, is left out
        assertEquals(1, p.page)
        assertEquals(true, p.hasNext)
        val gif = p.items[0]
        assertEquals("8041071659142944", gif.id)
        assertEquals("hello-hi-662", gif.slug)
        assertEquals("https://static.klipy.com/sm.jpg", gif.first("jpg", "md", "sm")?.url)
        assertEquals(1f, gif.aspect)
        assertEquals("https://static.klipy.com/md.gif", Media.link(gif))
    }

    @Test fun choosesWhatTheFieldTakes() {
        val (gif, sticker) = Klipy.parse(json).items
        val all: (String) -> Boolean = { true }
        Media.choose(Klipy.Kind.GIF, gif, all)!!.let {
            assertEquals("image/gif", it.mime)
            assertEquals("https://static.klipy.com/md.gif", it.file.url)
            assertEquals("8041071659142944.gif", it.fileName(gif))
        }
        // Only stills: the GIF's jpg.
        assertEquals("image/jpeg", Media.choose(Klipy.Kind.GIF, gif) { it == "image/jpeg" }!!.mime)
        // A sticker as WebP where taken, else PNG.
        assertEquals("https://static.klipy.com/g.webp", Media.choose(Klipy.Kind.STICKER, sticker, all)!!.file.url)
        assertEquals("image/png", Media.choose(Klipy.Kind.STICKER, sticker) { it == "image/png" }!!.mime)
        // A field that takes no pictures: nothing to send as one.
        assertNull(Media.choose(Klipy.Kind.GIF, gif) { false })
    }

    @Test fun refusesAnError() {
        assertEquals("bad key", runCatching { Klipy.parse("""{"result":false,"message":"bad key"}""") }.exceptionOrNull()?.message)
        assertEquals("The provided API key is invalid.", runCatching {
            Klipy.parse("""{"result":false,"errors":{"message":["The provided API key is invalid."]}}""")
        }.exceptionOrNull()?.message)
    }
}
