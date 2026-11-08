#include <jni.h>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "xdelta3.h"

namespace {
std::atomic<bool> cancelled{false};
constexpr int CANCELLED = -999;

class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value), chars_(env->GetStringUTFChars(value, nullptr)) {}
    ~UtfChars() { if (chars_) env_->ReleaseStringUTFChars(value_, chars_); }
    const char* get() const { return chars_; }
private:
    JNIEnv* env_; jstring value_; const char* chars_;
};

int decode_file(const char* source_name, const char* patch_name, const char* output_name,
                uint64_t maximum_output, size_t source_window, size_t input_window) {
    if (source_window < (1u << 20) || source_window > (16u << 20) ||
        input_window < (64u << 10) || input_window > (1u << 20)) return EINVAL;
    FILE* source_file = fopen(source_name, "rb");
    FILE* patch_file = fopen(patch_name, "rb");
    FILE* output_file = fopen(output_name, "wb");
    if (!source_file || !patch_file || !output_file) {
        int result = errno ? errno : EIO;
        if (source_file) fclose(source_file); if (patch_file) fclose(patch_file); if (output_file) fclose(output_file);
        return result;
    }
    uint8_t* source_buffer = static_cast<uint8_t*>(malloc(input_window));
    uint8_t* input_buffer = static_cast<uint8_t*>(malloc(input_window));
    if (!source_buffer || !input_buffer) {
        free(source_buffer); free(input_buffer); fclose(source_file); fclose(patch_file); fclose(output_file); return ENOMEM;
    }
    xd3_stream stream{}; xd3_config config{}; xd3_source source{};
    xd3_init_config(&config, XD3_ADLER32);
    config.winsize = static_cast<usize_t>(input_window);
    int result = xd3_config_stream(&stream, &config);
    if (result != 0) goto done;
    source.blksize = static_cast<usize_t>(input_window);
    source.max_winsize = static_cast<xoff_t>(source_window);
    source.curblk = source_buffer;
    source.onblk = fread(source_buffer, 1, input_window, source_file);
    source.curblkno = 0;
    result = xd3_set_source(&stream, &source);
    if (result != 0) goto done;
    {
        uint64_t total_output = 0;
        bool eof = false;
        while (!eof) {
            if (cancelled.load(std::memory_order_relaxed)) { result = CANCELLED; goto done; }
            size_t count = fread(input_buffer, 1, input_window, patch_file);
            if (ferror(patch_file)) { result = EIO; goto done; }
            eof = count < input_window;
            if (eof) xd3_set_flags(&stream, stream.flags | XD3_FLUSH);
            xd3_avail_input(&stream, input_buffer, static_cast<usize_t>(count));
            for (;;) {
                if (cancelled.load(std::memory_order_relaxed)) { result = CANCELLED; goto done; }
                result = xd3_decode_input(&stream);
                if (result == XD3_INPUT) break;
                if (result == XD3_OUTPUT) {
                    if (stream.avail_out > maximum_output - total_output) { result = EFBIG; goto done; }
                    if (fwrite(stream.next_out, 1, stream.avail_out, output_file) != stream.avail_out) { result = EIO; goto done; }
                    total_output += stream.avail_out; xd3_consume_output(&stream); continue;
                }
                if (result == XD3_GETSRCBLK) {
                    if (source.getblkno > UINT64_MAX / source.blksize) { result = EOVERFLOW; goto done; }
                    uint64_t offset = static_cast<uint64_t>(source.getblkno) * source.blksize;
                    if (fseeko(source_file, static_cast<off_t>(offset), SEEK_SET) != 0) { result = EIO; goto done; }
                    source.onblk = fread(source_buffer, 1, source.blksize, source_file);
                    if (ferror(source_file)) { result = EIO; goto done; }
                    source.curblkno = source.getblkno; continue;
                }
                if (result == XD3_GOTHEADER || result == XD3_WINSTART || result == XD3_WINFINISH) continue;
                goto done;
            }
        }
        result = xd3_close_stream(&stream);
        if (result == 0 && fflush(output_file) != 0) result = EIO;
    }
done:
    xd3_free_stream(&stream); free(source_buffer); free(input_buffer);
    fclose(source_file); fclose(patch_file); fclose(output_file);
    if (result != 0) remove(output_name);
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bytes14_redconpvp_patcher_PatchEngine_decode(JNIEnv* env, jclass, jstring source, jstring patch,
                                               jstring output, jlong maximum, jint source_window,
                                               jint input_window) {
    UtfChars source_chars(env, source), patch_chars(env, patch), output_chars(env, output);
    if (!source_chars.get() || !patch_chars.get() || !output_chars.get() || maximum <= 0) return EINVAL;
    cancelled.store(false, std::memory_order_relaxed);
    return decode_file(source_chars.get(), patch_chars.get(), output_chars.get(),
                       static_cast<uint64_t>(maximum), static_cast<size_t>(source_window),
                       static_cast<size_t>(input_window));
}

extern "C" JNIEXPORT void JNICALL
Java_com_bytes14_redconpvp_patcher_PatchEngine_cancel(JNIEnv*, jclass) {
    cancelled.store(true, std::memory_order_relaxed);
}
