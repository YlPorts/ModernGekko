#if defined(MODERNGEKKO_ANDROID_WITH_DOLPHIN)

#include <android/log.h>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "Core/Core.h"
#include "Core/HW/GBACore.h"
#include "Core/Host.h"
#include "Core/System.h"

namespace
{
constexpr const char* LOG_TAG = "ModernGekkoHost";
}

std::vector<std::string> Host_GetPreferredLocales()
{
  return {};
}

void Host_PPCSymbolsChanged() {}
void Host_PPCBreakpointsChanged() {}
bool Host_UIBlocksControllerState() { return false; }

void Host_Message(HostMessageID id)
{
  if (id == HostMessageID::WMUserStop)
    Core::QueueHostJob(&Core::Stop);
}

void Host_UpdateTitle(const std::string& title)
{
  __android_log_write(ANDROID_LOG_INFO, LOG_TAG, title.c_str());
}

void Host_UpdateDiscordClientID(const std::string&) {}

bool Host_UpdateDiscordPresenceRaw(const std::string&, const std::string&, const std::string&,
                                   const std::string&, const std::string&, const std::string&,
                                   std::int64_t, std::int64_t, int, int)
{
  return false;
}

void Host_UpdateDisasmDialog() {}
void Host_JitCacheInvalidation() {}
void Host_JitProfileDataWiped() {}
void Host_RequestRenderWindowSize(int, int) {}
bool Host_RendererHasFocus() { return true; }
bool Host_RendererHasFullFocus() { return true; }
bool Host_RendererIsFullscreen() { return true; }
bool Host_TASInputHasFocus() { return false; }
void Host_YieldToUI() {}
void Host_TitleChanged() {}

std::unique_ptr<GBAHostInterface> Host_CreateGBAHost(std::weak_ptr<HW::GBA::Core>)
{
  return nullptr;
}

#endif
