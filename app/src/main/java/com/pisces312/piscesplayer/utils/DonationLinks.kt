package com.pisces312.piscesplayer.utils

/**
 * Donation links shown in the app.
 *
 * Plain `https://` URLs only: the app never embeds a payment SDK, never starts a checkout
 * itself and never talks to a payment provider. F-Droid's metadata accepts `https://` donate
 * links, and an in-app payment integration would change how the app can be distributed.
 *
 * Afdian (爱发电) covers both WeChat Pay and Alipay on its own page and pays out to either,
 * which is why it is the only channel here instead of a bank of QR codes: a payment QR image
 * carries the payee's real name, and this repository is public.
 */
object DonationLinks {

    /** Afdian (爱发电) project page. The only donation channel; keep in sync with FUNDING.yml. */
    const val AfDianUrl = "https://afdian.com/a/pisces312"
}
