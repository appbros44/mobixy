import fs from 'fs'
import admin from 'firebase-admin'

let app

function getFirebaseApp() {
  if (app) return app

  const serviceAccountPath = process.env.FIREBASE_SERVICE_ACCOUNT_PATH
  if (!serviceAccountPath) {
    throw new Error('missing_firebase_service_account_path')
  }

  const raw = fs.readFileSync(serviceAccountPath, 'utf-8')
  const serviceAccount = JSON.parse(raw)

  app = admin.initializeApp({
    credential: admin.credential.cert(serviceAccount)
  })

  return app
}

export async function sendWakePush({ token, deviceId }) {
  getFirebaseApp()

  const message = {
    token,
    data: {
      kind: 'wake',
      deviceId: String(deviceId || '')
    },
    android: {
      priority: 'high'
    }
  }

  const id = await admin.messaging().send(message)
  return { id }
}
