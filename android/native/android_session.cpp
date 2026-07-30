#include "android_session.hpp"

#include "android_platform.hpp"

#include "AudioCommon/AudioCommon.h"
#include "Common/Config/Config.h"
#include "Common/FileUtil.h"
#include "Common/HookableEvent.h"
#include "Core/Boot/Boot.h"
#include "Core/Boot/BootManager.h"
#include "Core/Config/GraphicsSettings.h"
#include "Core/Config/MainSettings.h"
#include "Core/Core.h"
#include "Core/PowerPC/JitInterface.h"
#include "Core/PowerPC/PowerPC.h"
#include "Core/PowerPC/StaticRecomp/StaticRecompModuleSource.h"
#include "Core/System.h"
#include "DolphinNoGUI/Platform.h"
#include "UICommon/UICommon.h"
#include "VideoCommon/VideoConfig.h"
#include "moderngekko/cpu_state.h"
#include "moderngekko/game.hpp"
#include "moderngekko/module_loader.hpp"

#include <android/log.h>
#include <android/native_window.h>

#include <cstdint>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <utility>

namespace moderngekko::android
{
namespace
{
constexpr const char* LOG_TAG = "ModernGekkoSession";
std::mutex s_session_mutex;
Platform* s_platform = nullptr;
bool s_running = false;

SessionResult Failure(std::string message)
{
  __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, message.c_str());
  return {false, std::move(message)};
}

class SessionCleanup
{
public:
  ~SessionCleanup()
  {
    if (m_booted)
    {
      Core::Stop(Core::System::GetInstance());
      Core::Shutdown(Core::System::GetInstance());
    }
    m_state_hook = {};
    if (m_controllers)
      UICommon::ShutdownControllers();
    if (m_ui)
      UICommon::Shutdown();

    std::lock_guard lock(s_session_mutex);
    s_platform = nullptr;
    s_running = false;
  }

  Common::EventHook m_state_hook;
  bool m_ui = false;
  bool m_controllers = false;
  bool m_booted = false;
};
}  // namespace

SessionResult RunSession(const std::string& game_root, const std::string& user_directory,
                         const std::string& module_path, const std::string& sys_directory,
                         ANativeWindow* window)
{
  if (game_root.empty() || user_directory.empty() || module_path.empty() || sys_directory.empty())
    return Failure("faltan rutas obligatorias para iniciar el runtime");
  if (!window)
    return Failure("Android no proporcionó una superficie de renderizado");
  if (!std::filesystem::is_regular_file(module_path))
    return Failure("no se encontró el módulo recompilado ARM64");
  if (!std::filesystem::is_directory(sys_directory))
    return Failure("faltan los archivos Sys de Dolphin en el almacenamiento de la app");

  {
    std::lock_guard lock(s_session_mutex);
    if (s_running)
      return Failure("ya hay una sesión de ModernGekko en ejecución");
    s_running = true;
  }

  SessionCleanup cleanup;
  const GameInspectResult inspected = InspectGame(game_root);
  if (!inspected)
    return Failure("juego extraído inválido: " + inspected.error);

  const ModernGekkoModuleRequirements requirements = {
      GXRUNTIME_CPU_ABI_VERSION, static_cast<std::uint32_t>(sizeof(CPUState)),
      inspected.metadata->disc_id.c_str()};
  ModuleLibrary validation_library;
  const ModuleLoadResult module_result = validation_library.Open(module_path, requirements);
  if (module_result.status != ModuleLoadStatus::Ok)
  {
    std::string message = "el módulo recompilado fue rechazado";
    if (module_result.status == ModuleLoadStatus::DescriptorRejected)
      message += ": " + std::string(moderngekko_module_status_string(
                              module_result.validation_status));
    return Failure(std::move(message));
  }
  validation_library.Close();

  File::SetSysDirectory(sys_directory);
  UICommon::SetUserDirectory(user_directory);
  UICommon::CreateDirectories();
  UICommon::Init();
  cleanup.m_ui = true;

  std::unique_ptr<Platform> platform = CreatePlatform(window);
  if (!platform || !platform->Init())
    return Failure("no se pudo inicializar ANativeWindow");

  {
    std::lock_guard lock(s_session_mutex);
    s_platform = platform.get();
  }

  const WindowSystemInfo wsi = platform->GetWindowSystemInfo();
  UICommon::InitControllers(wsi);
  cleanup.m_controllers = true;

  Config::SetBase(Config::MAIN_FULLSCREEN, true);
  Config::SetBase(Config::MAIN_CPU_CORE, PowerPC::CPUCore::StaticRecomp);
  Config::SetBase(Config::MAIN_GFX_BACKEND, std::string("Vulkan"));
  Config::SetBase(Config::GFX_EFB_SCALE, 1);
  Config::SetBase(Config::GFX_SHADER_CACHE, true);
  Config::SetBase(Config::GFX_SHADER_COMPILATION_MODE,
                  ShaderCompilationMode::AsynchronousUberShaders);
  Config::SetBase(Config::GFX_WAIT_FOR_SHADERS_BEFORE_STARTING, true);
  Config::SetBase(Config::MAIN_INPUT_BACKGROUND_INPUT, false);

  const std::string audio_backend = AudioCommon::GetDefaultSoundBackend();
  if (!audio_backend.empty())
    Config::SetBase(Config::MAIN_AUDIO_BACKEND, audio_backend);

  auto& system = Core::System::GetInstance();
  StaticRecompModuleSource recomp_source = StaticRecompModuleSource::Dynamic(module_path);
  system.GetJitInterface().SetStaticRecompModuleSource(std::move(recomp_source));

  std::unique_ptr<BootParameters> boot =
      BootParameters::GenerateFromFile(inspected.metadata->main_dol.string());
  if (!boot)
    return Failure("Dolphin rechazó sys/main.dol");

  cleanup.m_state_hook = Core::AddOnStateChangedCallback([platform_ptr = platform.get()](Core::State state) {
    if (state == Core::State::Uninitialized && platform_ptr)
      platform_ptr->Stop();
  });

  if (!BootManager::BootCore(system, std::move(boot), wsi))
    return Failure("Dolphin no pudo arrancar sys/main.dol con el módulo recompilado");

  cleanup.m_booted = true;
  platform->MainLoop();

  Core::Stop(system);
  Core::Shutdown(system);
  cleanup.m_booted = false;
  system.GetJitInterface().SetStaticRecompModuleSource(StaticRecompModuleSource{});
  return {true, "la sesión terminó correctamente"};
}

void PauseSession()
{
  std::lock_guard lock(s_session_mutex);
  if (s_running)
    Core::SetState(Core::System::GetInstance(), Core::State::Paused);
}

void ResumeSession()
{
  std::lock_guard lock(s_session_mutex);
  if (s_running)
    Core::SetState(Core::System::GetInstance(), Core::State::Running);
}

void StopSession()
{
  std::lock_guard lock(s_session_mutex);
  if (s_platform)
    s_platform->RequestShutdown();
}
}  // namespace moderngekko::android
