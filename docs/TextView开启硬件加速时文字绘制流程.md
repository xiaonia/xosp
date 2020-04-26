
###                              TextView开启硬件加速时文字绘制流程

####  

[DisplayListOp.h](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r1/libs/hwui/DisplayListOp.h)

```cpp
class DrawTextOnPathOp : public DrawSomeTextOp {
public:
    ......
    virtual void applyDraw(OpenGLRenderer& renderer, Rect& dirty) override {
        renderer.drawTextOnPath(mText, mBytesCount, mCount, mPath,
                mHOffset, mVOffset, mPaint);
    }
    ......
};
```

开启硬件加速时，会将drawText操作封装成成 _DrawTextOnPathOp_对象，开始绘制时，则调用 _applyDraw_方法。



[OpenGLRenderer.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r1/libs/hwui/OpenGLRenderer.cpp)

```java
void OpenGLRenderer::drawTextOnPath(const char* text, int bytesCount, int count,
        const SkPath* path, float hOffset, float vOffset, const SkPaint* paint) {
    ......
    FontRenderer& fontRenderer = mCaches.fontRenderer->getFontRenderer(paint);
    fontRenderer.setFont(paint, SkMatrix::I());
    fontRenderer.setTextureFiltering(true);
    ......
    if (fontRenderer.renderTextOnPath(paint, clip, text, 0, bytesCount, count, path,
            hOffset, vOffset, hasLayer() ? &bounds : nullptr, &functor)) {
        dirtyLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, *currentTransform());
        mDirty = true;
    }
}
```

_applyDraw_ 方法接着调用 _OpenGLRenderer_ 的 _drawTextOnPath_ 方法。



[FontRenderer.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r1/libs/hwui/FontRenderer.cpp)

```cpp
bool FontRenderer::renderTextOnPath(const SkPaint* paint, const Rect* clip, const char *text,
        uint32_t startIndex, uint32_t len, int numGlyphs, const SkPath* path,
        float hOffset, float vOffset, Rect* bounds, TextDrawFunctor* functor) {
    ......
    mCurrentFont->render(paint, text, startIndex, len, numGlyphs, path, hOffset, vOffset);
    finishRender();
    return mDrawn;
}
```

_drawTextOnPath_ 方法继续调用 _FontRenderer_ 的 _renderTextOnPath_ 方法。



[Font.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-6.0.1_r1/libs/hwui/font/Font.cpp)

```cpp
void Font::render(const SkPaint* paint, const char *text, uint32_t start, uint32_t len,
        int numGlyphs, const SkPath* path, float hOffset, float vOffset) {
    ......
    while (glyphsCount < numGlyphs && penX < pathLength) {
        glyph_t glyph = GET_GLYPH(text);
        if (IS_END_OF_STRING(glyph)) {
            break;
        }
        CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph);
        penX += SkFixedToFloat(AUTO_KERN(prevRsbDelta, cachedGlyph->mLsbDelta));
        prevRsbDelta = cachedGlyph->mRsbDelta;
        if (cachedGlyph->mIsValid && cachedGlyph->mCacheTexture) {
            drawCachedGlyph(cachedGlyph, penX, hOffset, vOffset, measure, &position, &tangent);
        }
        penX += SkFixedToFloat(cachedGlyph->mAdvanceX);
        glyphsCount++;
    }
}
```

最后，_renderTextOnPath_ 方法会调用 _Font_ 的 _render_ 方法。在这里我们可以看到，对于每一个字符，_render_ 方法都会先查找字体缓存，如果有则直接使用字体缓存，如果没有则会创建并更新字体缓存。



```cpp
CachedGlyphInfo* Font::getCachedGlyph(const SkPaint* paint, glyph_t textUnit, bool precaching) {
    CachedGlyphInfo* cachedGlyph = mCachedGlyphs.valueFor(textUnit);
    if (cachedGlyph) {
        // Is the glyph still in texture cache?
        if (!cachedGlyph->mIsValid) {
            SkDeviceProperties deviceProperties(kUnknown_SkPixelGeometry, 1.0f);
            SkAutoGlyphCache autoCache(*paint, &deviceProperties, &mDescription.mLookupTransform);
            const SkGlyph& skiaGlyph = GET_METRICS(autoCache.getCache(), textUnit);
            updateGlyphCache(paint, skiaGlyph, autoCache.getCache(), cachedGlyph, precaching);
        }
    } else {
        cachedGlyph = cacheGlyph(paint, textUnit, precaching);
    }
    return cachedGlyph;
}
```

对于英文字符，只需要缓存26个字母即可；然而对于中文这一类字符，如果文本特别长，那么缓存的效果就会很不理想。参考 [深入探索Android卡顿优化（下）](https://juejin.im/post/5e49fc29e51d4526d326b056) 


