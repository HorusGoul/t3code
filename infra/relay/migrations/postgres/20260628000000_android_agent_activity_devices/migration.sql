ALTER TABLE "relay_mobile_devices" ALTER COLUMN "ios_major_version" DROP NOT NULL;--> statement-breakpoint
ALTER TABLE "relay_mobile_devices" ADD COLUMN "android_api_level" integer;--> statement-breakpoint
ALTER TABLE "relay_mobile_devices" ADD COLUMN "notification_channel_id" varchar(191);--> statement-breakpoint
ALTER TABLE "relay_mobile_devices" ADD COLUMN "alert_notification_channel_id" varchar(191);
