/*
 * Copyright 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "wifi_system/wifi.h"
#define LOG_TAG "WifiHW"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cutils/log.h>
#include <cutils/memory.h>
#include <cutils/misc.h>
#include <cutils/properties.h>
#include <private/android_filesystem_config.h>

#define _REALLY_INCLUDE_SYS__SYSTEM_PROPERTIES_H_
#include <sys/_system_properties.h>

#include "wifi_system/supplicant_manager.h"

#ifdef LIBWPA_CLIENT_EXISTS
#include <libwpa_client/wpa_ctrl.h>
#else
#define WPA_EVENT_TERMINATING "CTRL-EVENT-TERMINATING "
struct wpa_ctrl {};
struct wpa_ctrl* wpa_ctrl_open(const char* ctrl_path) {
  return NULL;
}
void wpa_ctrl_close(struct wpa_ctrl* ctrl) {}
int wpa_ctrl_request(struct wpa_ctrl* ctrl, const char* cmd, size_t cmd_len,
                     char* reply, size_t* reply_len,
                     void (*msg_cb)(char* msg, size_t len)) {
  return 0;
}
int wpa_ctrl_attach(struct wpa_ctrl* ctrl) { return 0; }
int wpa_ctrl_detach(struct wpa_ctrl* ctrl) { return 0; }
int wpa_ctrl_recv(struct wpa_ctrl* ctrl, char* reply, size_t* reply_len) {
  return 0;
}
int wpa_ctrl_get_fd(struct wpa_ctrl* ctrl) { return 0; }
#endif  // defined LIBWPA_CLIENT_EXISTS

namespace android {
namespace wifi_system {
namespace {

/* socket pair used to exit from a blocking read */
int exit_sockets[2];
struct wpa_ctrl* ctrl_conn;
struct wpa_ctrl* monitor_conn;

static char primary_iface[PROPERTY_VALUE_MAX];
// TODO: use new ANDROID_SOCKET mechanism, once support for multiple
// sockets is in

#define WIFI_TEST_INTERFACE "sta"

#define WIFI_DRIVER_LOADER_DELAY 1000000

const char IFACE_DIR[] = "/data/system/wpa_supplicant";

const char IFNAME[] = "IFNAME=";
#define IFNAMELEN (sizeof(IFNAME) - 1)
const char WPA_EVENT_IGNORE[] = "CTRL-EVENT-IGNORE ";

unsigned char dummy_key[21] = {0x02, 0x11, 0xbe, 0x33, 0x43, 0x35, 0x68,
                               0x47, 0x84, 0x99, 0xa9, 0x2b, 0x1c, 0xd3,
                               0xee, 0xff, 0xf1, 0xe2, 0xf3, 0xf4, 0xf5};

void wifi_close_sockets() {
  if (ctrl_conn != NULL) {
    wpa_ctrl_close(ctrl_conn);
    ctrl_conn = NULL;
  }

  if (monitor_conn != NULL) {
    wpa_ctrl_close(monitor_conn);
    monitor_conn = NULL;
  }

  if (exit_sockets[0] >= 0) {
    close(exit_sockets[0]);
    exit_sockets[0] = -1;
  }

  if (exit_sockets[1] >= 0) {
    close(exit_sockets[1]);
    exit_sockets[1] = -1;
  }
}

}  // namespace

const char kWiFiEntropyFile[] = "/data/misc/wifi/entropy.bin";

namespace {

int wifi_connect_on_socket_path(const char* path) {
  /* Make sure supplicant is running */
  android::wifi_system::SupplicantManager manager;
  if (!manager.IsSupplicantRunning()) {
    ALOGE("Supplicant not running, cannot connect");
    return -1;
  }

  ctrl_conn = wpa_ctrl_open(path);
  if (ctrl_conn == NULL) {
    ALOGE("Unable to open connection to supplicant on \"%s\": %s", path,
          strerror(errno));
    return -1;
  }
  monitor_conn = wpa_ctrl_open(path);
  if (monitor_conn == NULL) {
    wpa_ctrl_close(ctrl_conn);
    ctrl_conn = NULL;
    return -1;
  }
  if (wpa_ctrl_attach(monitor_conn) != 0) {
    wpa_ctrl_close(monitor_conn);
    wpa_ctrl_close(ctrl_conn);
    ctrl_conn = monitor_conn = NULL;
    return -1;
  }

  if (socketpair(AF_UNIX, SOCK_STREAM, 0, exit_sockets) == -1) {
    wpa_ctrl_close(monitor_conn);
    wpa_ctrl_close(ctrl_conn);
    ctrl_conn = monitor_conn = NULL;
    return -1;
  }

  return 0;
}

int wifi_send_command(const char* cmd, char* reply, size_t* reply_len) {
  int ret;
  if (ctrl_conn == NULL) {
    ALOGV("Not connected to wpa_supplicant - \"%s\" command dropped.\n", cmd);
    return -1;
  }
  ret = wpa_ctrl_request(ctrl_conn, cmd, strlen(cmd), reply, reply_len, NULL);
  if (ret == -2) {
    ALOGD("'%s' command timed out.\n", cmd);
    /* unblocks the monitor receive socket for termination */
    TEMP_FAILURE_RETRY(write(exit_sockets[0], "T", 1));
    return -2;
  } else if (ret < 0 || strncmp(reply, "FAIL", 4) == 0) {
    return -1;
  }
  if (strncmp(cmd, "PING", 4) == 0) {
    reply[*reply_len] = '\0';
  }
  return 0;
}

int wifi_ctrl_recv(char* reply, size_t* reply_len) {
  int res;
  int ctrlfd = wpa_ctrl_get_fd(monitor_conn);
  struct pollfd rfds[2];
  android::wifi_system::SupplicantManager manager;

  memset(rfds, 0, 2 * sizeof(struct pollfd));
  rfds[0].fd = ctrlfd;
  rfds[0].events |= POLLIN;
  rfds[1].fd = exit_sockets[1];
  rfds[1].events |= POLLIN;
  do {
    res = TEMP_FAILURE_RETRY(poll(rfds, 2, 30000));
    if (res < 0) {
      ALOGE("Error poll = %d", res);
      return res;
    } else if (res == 0) {
      /* timed out, check if supplicant is active
       * or not ..
       */
      if (!manager.IsSupplicantRunning()) {
        return -2;
      }
    }
  } while (res == 0);

  if (rfds[0].revents & POLLIN) {
    return wpa_ctrl_recv(monitor_conn, reply, reply_len);
  }

  /* it is not rfds[0], then it must be rfts[1] (i.e. the exit socket)
   * or we timed out. In either case, this call has failed ..
   */
  return -2;
}

int wifi_wait_on_socket(char* buf, size_t buflen) {
  size_t nread = buflen - 1;
  int result;
  char* match;
  char* match2;

  if (monitor_conn == NULL) {
    return snprintf(buf, buflen, "IFNAME=%s %s - connection closed",
                    primary_iface, WPA_EVENT_TERMINATING);
  }

  result = wifi_ctrl_recv(buf, &nread);

  /* Terminate reception on exit socket */
  if (result == -2) {
    return snprintf(buf, buflen, "IFNAME=%s %s - connection closed",
                    primary_iface, WPA_EVENT_TERMINATING);
  }

  if (result < 0) {
    ALOGD("wifi_ctrl_recv failed: %s\n", strerror(errno));
    return snprintf(buf, buflen, "IFNAME=%s %s - recv error", primary_iface,
                    WPA_EVENT_TERMINATING);
  }
  buf[nread] = '\0';
  /* Check for EOF on the socket */
  if (result == 0 && nread == 0) {
    /* Fabricate an event to pass up */
    ALOGD("Received EOF on supplicant socket\n");
    return snprintf(buf, buflen, "IFNAME=%s %s - signal 0 received",
                    primary_iface, WPA_EVENT_TERMINATING);
  }
  /*
   * Events strings are in the format
   *
   *     IFNAME=iface <N>CTRL-EVENT-XXX
   *        or
   *     <N>CTRL-EVENT-XXX
   *
   * where N is the message level in numerical form (0=VERBOSE, 1=DEBUG,
   * etc.) and XXX is the event name. The level information is not useful
   * to us, so strip it off.
   */

  if (strncmp(buf, IFNAME, IFNAMELEN) == 0) {
    match = strchr(buf, ' ');
    if (match != NULL) {
      if (match[1] == '<') {
        match2 = strchr(match + 2, '>');
        if (match2 != NULL) {
          nread -= (match2 - match);
          memmove(match + 1, match2 + 1, nread - (match - buf) + 1);
        }
      }
    } else {
      return snprintf(buf, buflen, "%s", WPA_EVENT_IGNORE);
    }
  } else if (buf[0] == '<') {
    match = strchr(buf, '>');
    if (match != NULL) {
      nread -= (match + 1 - buf);
      memmove(buf, match + 1, nread + 1);
      ALOGV("supplicant generated event without interface - %s\n", buf);
    }
  } else {
    /* let the event go as is! */
    ALOGW(
        "supplicant generated event without interface and without message "
        "level - %s\n",
        buf);
  }

  return nread;
}

}  // namespace

/* Establishes the control and monitor socket connections on the interface */
int wifi_connect_to_supplicant() {
  static char path[PATH_MAX];

  property_get("wifi.interface", primary_iface, WIFI_TEST_INTERFACE);

  if (access(IFACE_DIR, F_OK) == 0) {
    snprintf(path, sizeof(path), "%s/%s", IFACE_DIR, primary_iface);
  } else {
    snprintf(path, sizeof(path), "@android:wpa_%s", primary_iface);
  }
  return wifi_connect_on_socket_path(path);
}

void wifi_close_supplicant_connection() {
  int count =
      50; /* wait at most 5 seconds to ensure init has stopped stupplicant */

  wifi_close_sockets();

  android::wifi_system::SupplicantManager manager;
  while (count-- > 0) {
    if (!manager.IsSupplicantRunning()) {
      return;
    }
    usleep(100000);
  }
}

int wifi_wait_for_event(char* buf, size_t buflen) {
  return wifi_wait_on_socket(buf, buflen);
}

int wifi_command(const char* command, char* reply, size_t* reply_len) {
  return wifi_send_command(command, reply, reply_len);
}

int ensure_entropy_file_exists() {
  int ret;
  int destfd;

  ret = access(kWiFiEntropyFile, R_OK | W_OK);
  if ((ret == 0) || (errno == EACCES)) {
    if ((ret != 0) &&
        (chmod(kWiFiEntropyFile, S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP) != 0)) {
      ALOGE("Cannot set RW to \"%s\": %s", kWiFiEntropyFile, strerror(errno));
      return -1;
    }
    return 0;
  }
  destfd = TEMP_FAILURE_RETRY(open(kWiFiEntropyFile, O_CREAT | O_RDWR, 0660));
  if (destfd < 0) {
    ALOGE("Cannot create \"%s\": %s", kWiFiEntropyFile, strerror(errno));
    return -1;
  }

  if (TEMP_FAILURE_RETRY(write(destfd, dummy_key, sizeof(dummy_key))) !=
      sizeof(dummy_key)) {
    ALOGE("Error writing \"%s\": %s", kWiFiEntropyFile, strerror(errno));
    close(destfd);
    return -1;
  }
  close(destfd);

  /* chmod is needed because open() didn't set permisions properly */
  if (chmod(kWiFiEntropyFile, 0660) < 0) {
    ALOGE("Error changing permissions of %s to 0660: %s", kWiFiEntropyFile,
          strerror(errno));
    unlink(kWiFiEntropyFile);
    return -1;
  }

  return 0;
}

}  // namespace wifi_system
}  // namespace android
