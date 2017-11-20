/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "wifi_system/supplicant_manager.h"

#include <android-base/logging.h>
#include <cutils/properties.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// This ugliness is necessary to access internal implementation details
// of the property subsystem.
#define _REALLY_INCLUDE_SYS__SYSTEM_PROPERTIES_H_
#include <sys/_system_properties.h>

// Certain system (emulators) don't have wpa_supplicant installed at all.
#ifdef LIBWPA_CLIENT_EXISTS
#include <libwpa_client/wpa_ctrl.h>
#else
void wpa_ctrl_cleanup(void) {}
#endif

#include "wifi_system/wifi.h"

namespace android {
namespace wifi_system {
namespace {

const char kSupplicantInitProperty[] = "init.svc.wpa_supplicant";
const char kSupplicantConfigTemplatePath[] =
    "/system/etc/wifi/wpa_supplicant.conf";
const char kSupplicantConfigFile[] = "/data/misc/wifi/wpa_supplicant.conf";
const char kP2pConfigFile[] = "/data/misc/wifi/p2p_supplicant.conf";
const char kSupplicantServiceName[] = "wpa_supplicant";
constexpr mode_t kConfigFileMode = S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP;

int ensure_config_file_exists(const char* config_file) {
  char buf[2048];
  int srcfd, destfd;
  int nread;
  int ret;

  ret = access(config_file, R_OK | W_OK);
  if ((ret == 0) || (errno == EACCES)) {
    if ((ret != 0) && (chmod(config_file, kConfigFileMode) != 0)) {
      LOG(ERROR) << "Cannot set RW to \"" << config_file << "\": "
                 << strerror(errno);
      return false;
    }
    return true;
  } else if (errno != ENOENT) {
    LOG(ERROR) << "Cannot access \"" << config_file << "\": "
               << strerror(errno);
    return false;
  }

  srcfd = TEMP_FAILURE_RETRY(open(kSupplicantConfigTemplatePath, O_RDONLY));
  if (srcfd < 0) {
    LOG(ERROR) << "Cannot open \"" << kSupplicantConfigTemplatePath << "\": "
               << strerror(errno);
    return false;
  }

  destfd = TEMP_FAILURE_RETRY(open(config_file,
                                   O_CREAT | O_RDWR,
                                   kConfigFileMode));
  if (destfd < 0) {
    close(srcfd);
    LOG(ERROR) << "Cannot create \"" << config_file << "\": "
               << strerror(errno);
    return false;
  }

  while ((nread = TEMP_FAILURE_RETRY(read(srcfd, buf, sizeof(buf)))) != 0) {
    if (nread < 0) {
      LOG(ERROR) << "Error reading \"" << kSupplicantConfigTemplatePath
                 << "\": " << strerror(errno);
      close(srcfd);
      close(destfd);
      unlink(config_file);
      return false;
    }
    TEMP_FAILURE_RETRY(write(destfd, buf, nread));
  }

  close(destfd);
  close(srcfd);

  /* chmod is needed because open() didn't set permisions properly */
  if (chmod(config_file, kConfigFileMode) < 0) {
    LOG(ERROR) << "Error changing permissions of " << config_file
               << " to 0660: " << strerror(errno);
    unlink(config_file);
    return false;
  }

  return true;
}

}  // namespace

bool SupplicantManager::StartSupplicant() {
  char supp_status[PROPERTY_VALUE_MAX] = {'\0'};
  int count = 200; /* wait at most 20 seconds for completion */
  const prop_info* pi;
  unsigned serial = 0;

  /* Check whether already running */
  if (property_get(kSupplicantInitProperty, supp_status, NULL) &&
      strcmp(supp_status, "running") == 0) {
    return true;
  }

  /* Before starting the daemon, make sure its config file exists */
  if (ensure_config_file_exists(kSupplicantConfigFile) < 0) {
    LOG(ERROR) << "Wi-Fi will not be enabled";
    return false;
  }

  /*
   * Some devices have another configuration file for the p2p interface.
   * However, not all devices have this, and we'll let it slide if it
   * is missing.  For devices that do expect this file to exist,
   * supplicant will refuse to start and emit a good error message.
   * No need to check for it here.
   */
  (void)ensure_config_file_exists(kP2pConfigFile);

  if (ensure_entropy_file_exists() < 0) {
    LOG(ERROR) << "Wi-Fi entropy file was not created";
  }

  /* Clear out any stale socket files that might be left over. */
  wpa_ctrl_cleanup();

  /*
   * Get a reference to the status property, so we can distinguish
   * the case where it goes stopped => running => stopped (i.e.,
   * it start up, but fails right away) from the case in which
   * it starts in the stopped state and never manages to start
   * running at all.
   */
  pi = __system_property_find(kSupplicantInitProperty);
  if (pi != NULL) {
    serial = __system_property_serial(pi);
  }

  property_set("ctl.start", kSupplicantServiceName);
  sched_yield();

  while (count-- > 0) {
    if (pi == NULL) {
      pi = __system_property_find(kSupplicantInitProperty);
    }
    if (pi != NULL) {
      /*
       * property serial updated means that init process is scheduled
       * after we sched_yield, further property status checking is based on this
       */
      if (__system_property_serial(pi) != serial) {
        __system_property_read(pi, NULL, supp_status);
        if (strcmp(supp_status, "running") == 0) {
          return true;
        } else if (strcmp(supp_status, "stopped") == 0) {
          return false;
        }
      }
    }
    usleep(100000);
  }
  return false;
}

bool SupplicantManager::StopSupplicant() {
  char supp_status[PROPERTY_VALUE_MAX] = {'\0'};
  int count = 50; /* wait at most 5 seconds for completion */

  /* Check whether supplicant already stopped */
  if (property_get(kSupplicantInitProperty, supp_status, NULL) &&
      strcmp(supp_status, "stopped") == 0) {
    return true;
  }

  property_set("ctl.stop", kSupplicantServiceName);
  sched_yield();

  while (count-- > 0) {
    if (property_get(kSupplicantInitProperty, supp_status, NULL)) {
      if (strcmp(supp_status, "stopped") == 0) return true;
    }
    usleep(100000);
  }
  LOG(ERROR) << "Failed to stop supplicant";
  return false;
}

bool SupplicantManager::IsSupplicantRunning() {
  char supp_status[PROPERTY_VALUE_MAX] = {'\0'};
  if (property_get(kSupplicantInitProperty, supp_status, NULL)) {
    return strcmp(supp_status, "running") == 0;
  }
  return false;  // Failed to read service status from init.
}

}  // namespace wifi_system
}  // namespace android
