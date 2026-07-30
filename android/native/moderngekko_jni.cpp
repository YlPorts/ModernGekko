#include "moderngekko/game.hpp"

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <exception>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <string_view>

#if defined(MODERNGEKKO_ANDROID_WITH_DOLPHIN)
#include "DiscIO/DiscExtractor.h"
#include "DiscIO/Filesystem.h"
#include "DiscIO/Volume.h"
#include "DiscIO/VolumeDisc.h"
#endif

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

std::string FromJString(JNIEnv* env, jstring value)
{
  if (!value)
    return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (!chars)
    return {};
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

std::string ErrorJson(std::string_view message)
{
  return "{\"ok\":false,\"error\":\"" + JsonEscape(message) + "\"}";
}

std::string InspectionJson(const moderngekko::GameMetadata& game)
{
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
  return json.str();
}

std::uint32_t ReadBE32(const unsigned char* bytes)
{
  return (std::uint32_t{bytes[0]} << 24) | (std::uint32_t{bytes[1]} << 16) |
         (std::uint32_t{bytes[2]} << 8) | bytes[3];
}

std::string InspectRawDiscHeader(const std::string& path)
{
  std::ifstream file(path, std::ios::binary);
  if (!file)
    return ErrorJson("no se pudo abrir la imagen seleccionada");

  std::array<unsigned char, 0x60> header{};
  if (!file.read(reinterpret_cast<char*>(header.data()), header.size()))
    return ErrorJson("la imagen es demasiado pequeña o no se puede leer");

  std::string id(reinterpret_cast<const char*>(header.data()), 6);
  if (!std::ranges::all_of(id, [](unsigned char c) { return std::isalnum(c) != 0; }))
    return ErrorJson("la imagen no tiene un ID válido de GameCube/Wii");

  const bool wii = ReadBE32(header.data() + 0x18) == 0x5d1c9ea3;
  const bool gamecube = ReadBE32(header.data() + 0x1c) == 0xc2339f3d;
  if (!wii && !gamecube)
    return ErrorJson("el archivo no parece una imagen válida de GameCube o Wii");

  std::string name(reinterpret_cast<const char*>(header.data() + 0x20), 0x40);
  if (const std::size_t end = name.find('\0'); end != std::string::npos)
    name.resize(end);
  while (!name.empty() && std::isspace(static_cast<unsigned char>(name.back())))
    name.pop_back();

  return "{\"ok\":true,\"discId\":\"" + JsonEscape(id) +
         "\",\"gameName\":\"" + JsonEscape(name) + "\",\"platform\":\"" +
         (wii ? "Wii" : "GameCube") + "\"}";
}

std::string InspectExtracted(const std::string& root)
{
  const moderngekko::GameInspectResult inspected = moderngekko::InspectGame(root);
  if (!inspected)
    return ErrorJson(inspected.error);
  return InspectionJson(*inspected.metadata);
}

#if defined(MODERNGEKKO_ANDROID_WITH_DOLPHIN)
std::string ExtractDisc(const std::string& image_path, const std::string& output_root)
{
  std::unique_ptr<DiscIO::VolumeDisc> volume = DiscIO::CreateDisc(image_path);
  if (!volume)
    return ErrorJson("Dolphin no pudo abrir la imagen. Comprueba que no esté dañada");

  DiscIO::Partition partition = volume->GetGamePartition();
  if (partition == DiscIO::PARTITION_NONE && !volume->GetPartitions().empty())
    partition = volume->GetPartitions().front();

  const DiscIO::FileSystem* filesystem = volume->GetFileSystem(partition);
  if (!filesystem || !filesystem->IsValid())
    return ErrorJson("no se pudo leer el sistema de archivos de la partición del juego");

  std::error_code ec;
  std::filesystem::create_directories(std::filesystem::path(output_root) / "files", ec);
  if (ec)
    return ErrorJson("no se pudo crear la carpeta de extracción: " + ec.message());

  if (!DiscIO::ExportSystemData(*volume, partition, output_root))
    return ErrorJson("falló la extracción de sys/main.dol y los datos de arranque");

  DiscIO::ExportDirectory(*volume, partition, filesystem->GetRoot(), true, "",
                          (std::filesystem::path(output_root) / "files").string(),
                          [](const std::string& current) {
                            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Extracting %s",
                                                current.c_str());
                            return false;
                          });

  return InspectExtracted(output_root);
}
#endif
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
#if defined(MODERNGEKKO_ANDROID_WITH_DOLPHIN)
  return ToJString(env, "ModernGekko Android bridge 0.2 + Dolphin DiscIO");
#else
  return ToJString(env, "ModernGekko Android bridge 0.2 (inspector only)");
#endif
}

JNIEXPORT jstring JNICALL
Java_org_moderngekko_android_NativeBridge_inspectExtractedGame(JNIEnv* env, jclass,
                                                                jstring game_root)
{
  try
  {
    const std::string root = FromJString(env, game_root);
    if (root.empty())
      return ToJString(env, ErrorJson("game root is empty"));
    return ToJString(env, InspectExtracted(root));
  }
  catch (const std::exception& error)
  {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Inspection failed: %s", error.what());
    return ToJString(env, ErrorJson(error.what()));
  }
  catch (...)
  {
    return ToJString(env, ErrorJson("unknown native exception"));
  }
}

JNIEXPORT jstring JNICALL
Java_org_moderngekko_android_NativeBridge_inspectDiscImage(JNIEnv* env, jclass,
                                                            jstring image_path)
{
  try
  {
    const std::string path = FromJString(env, image_path);
    return ToJString(env, path.empty() ? ErrorJson("image path is empty") : InspectRawDiscHeader(path));
  }
  catch (const std::exception& error)
  {
    return ToJString(env, ErrorJson(error.what()));
  }
}

JNIEXPORT jstring JNICALL
Java_org_moderngekko_android_NativeBridge_extractDiscImage(JNIEnv* env, jclass,
                                                            jstring image_path,
                                                            jstring output_root)
{
  try
  {
    const std::string image = FromJString(env, image_path);
    const std::string output = FromJString(env, output_root);
    if (image.empty() || output.empty())
      return ToJString(env, ErrorJson("image path or output root is empty"));
#if defined(MODERNGEKKO_ANDROID_WITH_DOLPHIN)
    return ToJString(env, ExtractDisc(image, output));
#else
    return ToJString(env, ErrorJson("this APK was built without Dolphin DiscIO extraction"));
#endif
  }
  catch (const std::exception& error)
  {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Extraction failed: %s", error.what());
    return ToJString(env, ErrorJson(error.what()));
  }
  catch (...)
  {
    return ToJString(env, ErrorJson("unknown extraction exception"));
  }
}

JNIEXPORT jstring JNICALL
Java_org_moderngekko_android_NativeBridge_runGame(JNIEnv* env, jclass, jstring, jstring, jobject)
{
  return ToJString(env, ErrorJson(
      "La importación ya está lista. La conexión final del renderizador y el módulo ARM64 sigue en construcción."));
}

JNIEXPORT void JNICALL
Java_org_moderngekko_android_NativeBridge_updateSurface(JNIEnv*, jclass, jobject, jint, jint)
{
}

JNIEXPORT void JNICALL Java_org_moderngekko_android_NativeBridge_clearSurface(JNIEnv*, jclass) {}
JNIEXPORT void JNICALL Java_org_moderngekko_android_NativeBridge_pauseGame(JNIEnv*, jclass) {}
JNIEXPORT void JNICALL Java_org_moderngekko_android_NativeBridge_resumeGame(JNIEnv*, jclass) {}
JNIEXPORT void JNICALL Java_org_moderngekko_android_NativeBridge_stopGame(JNIEnv*, jclass) {}
}
