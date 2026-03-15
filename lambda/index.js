/**
 * vault-vibes-notification-handler
 *
 * Triggered by EventBridge when the application publishes events to
 * the "vault-vibes-events" bus with source "vaultvibes.finance".
 *
 * Supported detail-type values:
 *   LOAN_APPROVED
 *   LOAN_ISSUED
 *   CONTRIBUTION_OVERDUE
 *   DISTRIBUTION_EXECUTED
 *
 * Required environment variables:
 *   WHATSAPP_TOKEN    — Meta Cloud API bearer token
 *   WHATSAPP_PHONE_ID — WhatsApp Business phone number ID
 */

const https = require('https');

const WHATSAPP_TOKEN    = process.env.WHATSAPP_TOKEN;
const WHATSAPP_PHONE_ID = process.env.WHATSAPP_PHONE_ID;
const GRAPH_API_VERSION = 'v19.0';

// ---------------------------------------------------------------------------
// Message templates per event type
// ---------------------------------------------------------------------------
function buildMessage(detailType, detail) {
  const amount = detail.amount != null
    ? `R ${Number(detail.amount).toFixed(2)}`
    : null;

  switch (detailType) {
    case 'LOAN_APPROVED':
      return `✅ *Vault Vibes* — Your loan of ${amount} has been *approved*. Funds will be issued shortly.`;

    case 'LOAN_ISSUED':
      return `💸 *Vault Vibes* — Your loan of ${amount} has been *issued*. Please repay by month end.`;

    case 'CONTRIBUTION_OVERDUE':
      return `⚠️ *Vault Vibes* — Your monthly contribution of ${amount} is *overdue*. Please pay as soon as possible.`;

    case 'DISTRIBUTION_EXECUTED':
      return `🎉 *Vault Vibes* — A distribution of ${amount} has been *paid out* to your account.`;

    default:
      return `📣 *Vault Vibes* — You have a new notification: ${detailType}.`;
  }
}

// ---------------------------------------------------------------------------
// WhatsApp Cloud API — send a text message
// ---------------------------------------------------------------------------
function sendWhatsAppMessage(to, text) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify({
      messaging_product: 'whatsapp',
      recipient_type: 'individual',
      to,
      type: 'text',
      text: { preview_url: false, body: text },
    });

    const options = {
      hostname: 'graph.facebook.com',
      path: `/${GRAPH_API_VERSION}/${WHATSAPP_PHONE_ID}/messages`,
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${WHATSAPP_TOKEN}`,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
      },
    };

    const req = https.request(options, (res) => {
      let body = '';
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          console.log(`WhatsApp message sent to ${to}:`, body);
          resolve(JSON.parse(body));
        } else {
          console.error(`WhatsApp API error (${res.statusCode}):`, body);
          reject(new Error(`WhatsApp API responded with ${res.statusCode}: ${body}`));
        }
      });
    });

    req.on('error', (err) => {
      console.error('WhatsApp request error:', err.message);
      reject(err);
    });

    req.write(payload);
    req.end();
  });
}

// ---------------------------------------------------------------------------
// Lambda handler
// ---------------------------------------------------------------------------
exports.handler = async (event) => {
  console.log('Received EventBridge event:', JSON.stringify(event, null, 2));

  const detailType = event['detail-type'];
  const detail     = event.detail || {};

  if (!detail.phoneNumber) {
    console.warn('No phoneNumber in event detail — skipping WhatsApp notification.');
    return { statusCode: 200, body: 'No phoneNumber; skipped.' };
  }

  if (!WHATSAPP_TOKEN || !WHATSAPP_PHONE_ID) {
    throw new Error('WHATSAPP_TOKEN and WHATSAPP_PHONE_ID environment variables must be set.');
  }

  const message = buildMessage(detailType, detail);
  console.log(`Sending ${detailType} notification to ${detail.phoneNumber}`);

  await sendWhatsAppMessage(detail.phoneNumber, message);

  return { statusCode: 200, body: 'Notification sent.' };
};

