// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

/**
 * Returns `true` if [t] looks like a "tree URI permission was revoked" failure (the
 * user removed the folder grant in the storage provider's app, the folder was deleted,
 * etc.). Backed on Android by `t is SecurityException`; iOS will never reach this code
 * path in v1, but the contract is the same.
 */
expect fun isUriPermissionRevoked(t: Throwable): Boolean
