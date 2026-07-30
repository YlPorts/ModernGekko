#pragma once

#include <memory>

class Platform;
struct ANativeWindow;

namespace moderngekko::android
{
std::unique_ptr<Platform> CreatePlatform(ANativeWindow* window);
void UpdateSurface(ANativeWindow* window);
void ClearSurface();
}  // namespace moderngekko::android
