#include "moderngekko/game.hpp"

#include <android/log.h>
#include <jni.h>

#include <exception>
#include <sstream>
#include <string>
#include <string_view>

namespace
{
constexpr const char* LOG_TAG = "ModernGekkoAndroid";

std::string JsonEscape(std::string_view value)
{
  std::string escaped;
  escaped.reserve(value.size() + 16);
  for (const unsigned char c : value)
  {
    switch (c)
    {
    case '"':
      escaped += "\\\"";
      break;
    case '\\':
      escaped += "\\\\";
      break;
    case '\b':
      escaped += "\\b";
      break;
    case '\f':
      escaped += "\\f";
      break;
    case '\n':
      escaped += "\\n";
      break;
    case '\r':
      escaped += "\\r";
      break;
    case '\t':
      escaped += "\\t";
      break;
    default:
      if (c < 0x20)
      {
        constexpr char hex[] = "0123456789abcdef";
        escaped += "\\u00";
        escaped += hex[(c >> 4) & 0x0f];
        escaped += hex[c & 0x0f];
      }
      else
      {
        escaped += static_cast<char>(c);
      }
      break;
    }
  }
  return escaped;
}

jstring ToJString(JNIEnv* env, const std::string& value)
{
  return env->NewStringUTF(value.c_str());
}

std::string ErrorJson(std::string_view message)
{
  return "{\"ok\":false,\"error\":\"" + JsonEscape(message) + "\"}";
}
}  // namespace

extern "C"
{
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*)
{
  __android_log_write(ANDROID_LOG_INFO, LOG_TAG, "ModernGekko JNI bridge loaded");
  return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_org_moderngekko_android_NativeBridge_nativeVersion(JNIEnv* env, jclass)
{
  return ToJString(env, "ModernGekko Android bridge 0.1");
}

JNIEXPORT jstring JNICALL
Java_org_moderngekko_android_NativeBridge_inspectExtractedGame(JNIEnv* env, jclass,
                                                               jstring game_root)
{
  if (game_root == nullptr)
    return ToJString(env, ErrorJson("game root is null"));

  const char* root_chars = env->GetStringUTFChars(game_root, nullptr);
  if (root_chars == nullptr)
    return ToJString(env, ErrorJson("could not read game root"));

  const std::string root(root_chars);
  env->ReleaseStringUTFChars(game_root, root_chars);

  try
  {
    const moderngekko::GameInspectResult inspected = moderngekko::InspectGame(root);
    if (!inspected)
      return ToJString(env, ErrorJson(inspected.error));

    const moderngekko::GameMetadata& game = *inspected.metadata;
    std::ostringstream json;
    json << "{\"ok\":true"
         << ",\"gameName\":\"" << JsonEscape(game.game_name) << "\""
         << ",\"discId\":\"" << JsonEscape(game.disc_id) << "\""
         << ",\"platform\":\""
         << (game.platform == moderngekko::GamePlatform::Wii ? "Wii" : "GameCube") << "\""
         << ",\"entryPoint\":" << game.entry_point
         << ",\"dolSha256\":\"" << game.dol_sha256 << "\""
         << ",\"relSha256\":\"" << game.rel_sha256 << "\""
         << ",\"assetsSha256\":\"" << game.assets_sha256 << "\"}";
    return ToJString(env, json.str());
  }
  catch (const std::exception& error)
  {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Inspection failed: %s", error.what());
    return ToJString(env, ErrorJson(error.what()));
  }
  catch (...)
  {
    __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, "Inspection failed with unknown exception");
    return ToJString(env, ErrorJson("unknown native exception"));
  }
}
}
