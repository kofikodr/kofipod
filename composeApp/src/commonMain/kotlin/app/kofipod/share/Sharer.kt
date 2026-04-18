// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.share

expect class Sharer {
    fun shareText(title: String, text: String)
}
