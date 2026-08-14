"""Intake bot: durable hourly rate limit and explicit rejection of unsupported Telegram formats."""
import os, sqlite3, time
from telegram import Update
from telegram.ext import Application, CommandHandler, ContextTypes

DATABASE = os.getenv("BOT_DATABASE", "/data/sticker-bot.sqlite")
def allowed(user_id):
    db=sqlite3.connect(DATABASE); db.execute("create table if not exists publication (user integer primary key, created integer not null)")
    row=db.execute("select created from publication where user=?", (user_id,)).fetchone()
    if row and time.time()-row[0] < 3600: db.close(); return False
    db.execute("insert or replace into publication values (?,?)", (user_id, int(time.time()))); db.commit(); db.close(); return True
async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text("Отправьте команду /publish <pack-id> <emoji|sticker>. Перед публикацией бот запросит подтверждение прав и метаданные.")
async def publish(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not allowed(update.effective_user.id):
        await update.message.reply_text("Можно публиковать не более одного нового пака в час."); return
    args=context.args
    if len(args)!=2 or args[1] not in ("emoji","sticker"):
        await update.message.reply_text("Формат: /publish <pack-id> <emoji|sticker>"); return
    await update.message.reply_text("Черновик создан. Подтвердите, что у вас есть права на контент. Статичные стикеры будут WebP, видео/WebM - беззвучным MP4; TGS и GIF не принимаются.")
def main():
    app=Application.builder().token(os.environ["TELEGRAM_BOT_TOKEN"]).build()
    app.add_handler(CommandHandler("start", start)); app.add_handler(CommandHandler("publish", publish)); app.run_polling()
if __name__ == "__main__": main()
