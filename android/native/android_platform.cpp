#include "android_platform.hpp"

#include <android/log.h>
#include <android/native_window.h>

#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "Core/Core.h"
#include "Core/System.h"
#include "DolphinNoGUI/Platform.h"
#include "VideoCommon/Present.h"

namespace moderngekko::android
{
namespace
{
constexpr const char* LOG_TAG = "ModernGekkoPlatform";
std::mutex s_active_mutex;

class AndroidPlatform final : public Platform
{
public:
  explicit AndroidPlatform(ANativeWindow* window)
  {
    m_window_fullscreen = true;
    ReplaceSurface(window);
    std::lock_guard lock(s_active_mutex);
    s_active = this;
  }

  ~AndroidPlatform() override
  {
    {
      std::lock_guard lock(s_active_mutex);
      if (s_active == this)
        s_active = nullptr;
    }
    ReplaceSurface(nullptr);
  }

  bool Init() override
  {
    std::lock_guard lock(m_surface_mutex);
    return m_window != nullptr;
  }

  void SetTitle(const std::string& title) override
  {
    __android_log_write(ANDROID_LOG_INFO, LOG_TAG, title.c_str());
  }

  void MainLoop() override
  {
    auto& system = Core::System::GetInstance();
    while (IsRunning())
    {
      UpdateRunningFlag();
      Core::HostDispatchJobs(system);
      if (Core::GetState(system) == Core::State::Uninitialized)
        break;
      std::this_thread::sleep_for(std::chrono::milliseconds(8));
    }
  }

  WindowSystemInfo GetWindowSystemInfo() const override
  {
    std::lock_guard lock(m_surface_mutex);
    WindowSystemInfo wsi(WindowSystemType::Android, nullptr, m_window, m_window);
    wsi.render_surface_scale = 1.0f;
    return wsi;
  }

  void ReplaceSurface(ANativeWindow* window)
  {
    if (window)
      ANativeWindow_acquire(window);

    std::lock_guard lock(m_surface_mutex);
    if (g_presenter)
      g_presenter->ChangeSurface(window);
    if (m_window)
      ANativeWindow_release(m_window);
    m_window = window;
  }

  static AndroidPlatform* s_active;

private:
  mutable std::mutex m_surface_mutex;
  ANativeWindow* m_window = nullptr;
};

AndroidPlatform* AndroidPlatform::s_active = nullptr;
}  // namespace

std::unique_ptr<Platform> CreatePlatform(ANativeWindow* window)
{
  return std::make_unique<AndroidPlatform>(window);
}

void UpdateSurface(ANativeWindow* window)
{
  std::lock_guard lock(s_active_mutex);
  if (AndroidPlatform::s_active)
    AndroidPlatform::s_active->ReplaceSurface(window);
}

void ClearSurface()
{
  UpdateSurface(nullptr);
}
}  // namespace moderngekko::android
