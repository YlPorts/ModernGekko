#pragma once

#include <string>

struct ANativeWindow;

namespace moderngekko::android
{
struct SessionResult
{
  bool ok = false;
  std::string message;
};

SessionResult RunSession(const std::string& game_root, const std::string& user_directory,
                         const std::string& module_path, const std::string& sys_directory,
                         ANativeWindow* window);
void PauseSession();
void ResumeSession();
void StopSession();
}  // namespace moderngekko::android
