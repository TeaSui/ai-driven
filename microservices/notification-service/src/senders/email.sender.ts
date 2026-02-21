import nodemailer from 'nodemailer';
import { Logger } from '../utils/logger';

const logger = new Logger('notification-service:email');

export class EmailSender {
  private transporter: nodemailer.Transporter;

  constructor() {
    this.transporter = nodemailer.createTransport({
      host: process.env.SMTP_HOST || 'localhost',
      port: parseInt(process.env.SMTP_PORT || '587', 10),
      secure: process.env.SMTP_SECURE === 'true',
      auth: {
        user: process.env.SMTP_USER,
        pass: process.env.SMTP_PASS,
      },
    });
  }

  async send(options: { to: string; subject: string; html: string; text?: string }): Promise<void> {
    const info = await this.transporter.sendMail({
      from: process.env.SMTP_FROM || 'noreply@crm.app',
      to: options.to,
      subject: options.subject,
      html: options.html,
      text: options.text,
    });

    logger.info('Email sent', { messageId: info.messageId, to: options.to });
  }

  async verify(): Promise<boolean> {
    try {
      await this.transporter.verify();
      return true;
    } catch {
      return false;
    }
  }
}
