import { PrismaClient } from '@prisma/client'

let prisma

export function getDb() {
  if (!prisma) {
    prisma = new PrismaClient()
  }
  return prisma
}

export async function closeDb() {
  if (prisma) {
    await prisma.$disconnect()
    prisma = null
  }
}
