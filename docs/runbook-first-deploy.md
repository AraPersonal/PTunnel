# رانبوک: اولین استقرار روی GitHub و اولین باکس

این فایل همان چیزی است که یک نفر جدید در تیم باید انجام دهد تا از صفر به یک محیطِ stage برسد.

## ۰) پیش‌نیازِ محلی

- Node 22+، Git، Ansible، Docker (برای build محلی تصاویر).
- دسترسیِ نوشتن به `github.com/AraPersonal/PTunnel` با یک PAT یا Git Credential Manager.
- **یک پوشهٔ خصوصی برای سکرت‌ها** (`~/.ptunnel/`) که هرگز داخل این ریپو نیست.

## ۰.۵) اول خصوصی، بعد push

تاریخچهٔ عمومی را نمی‌توان un-publish کرد. قبل از هر push:
GitHub → Settings → General → Danger Zone → Change visibility → **Private**.

## ۱) اولین push

ریپوی远端 یک کامیتِ README دارد و تاریخچهٔ ما مستقل است. دو راه:

**الف) تاریخچهٔ تمیز (انتخاب‌شده):**

    make publish-init      # گارد: اسکن سکرت + درخت تمیز + تأیید تایپی، سپس force-with-lease
    # یا به‌صورت دستی:
    git push --force-with-lease origin main

**ب) بدون بازنویسیِ تاریخچه:**

    git push origin main:v2
    # سپس روی GitHub یک Pull Request از v2 به main باز و merge کنید

## ۲) تنظیمات ریپو (یک‌بار)

> وضعیت تا این لحظه: ریپو **private** است، محیط‌های  و  ساخته شده‌اند، و هر دو
> workflow روی  سبزند. تنها چیزی که با توکنِ فعلی اعمال نشد **محافظتِ شاخه** بود
> (HTTP 403 نیاز به scope مدیریتی دارد)؛ نامِ دقیقِ checkهایی که باید اجباری شوند:
> , , . بازبینِ Environmentِ  را هم در UI تعیین کنید
> (API برای مالکِ همان Environment خطای 422 می‌دهد).


- Settings → Branches → محافظتِ `main`: Require PR، Require approvals = 1، Require status checks = `panel`, `hygiene`, `infra`، Dismiss stale approvals، Include administrators.
- Settings → Environments → ساختِ `stage` و `prod`:
  - `prod`: Required reviewers = ۱ نفر.
  - Secrets هر محیط (جدا، هیچ اشتراکی):
    `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`, `DEPLOY_HOST`, `DEPLOY_USER`, `PUBLIC_URL`.
- Settings → Actions → General: Workflow permissions = Read; اجازهٔ نوشتنِ Packages برای `images` (پیش‌فرضِ `GITHUB_TOKEN` کافی است).
- پس از اولین build: Packages → `panel` و `xray` → Visibility = Private.

## ۳) کلیدها و ولت

    ansible-vault create infra/ansible/inventories/stage/group_vars/vault.yml
    # مقادیر لازم: vault_vless_id, vault_reality_private_key, vault_reality_short_id,
    #               vault_kcp_seed, vault_ghcr_token

`vault.yml` هرگز کامیت نمی‌شود (در `.gitignore`).

## ۴) اولین باکس stage

1. یک VPS تازه (Ubuntu 22.04/24.04) با کلید SSH.
2. `ansible_host` را در `infra/ansible/inventories/stage/hosts.yml` عوض کنید (PR).
3. `make infra-check ENV=stage` سپس:

       ANSIBLE_CONFIG=infra/ansible/ansible.cfg \
         ansible-playbook -i infra/ansible/inventories/stage/hosts.yml \
         infra/ansible/playbooks/site.yml --ask-vault-pass

4. تأیید: `curl -sS http://<stage-ip>/healthz` و سپس `/readyz` باید ۲۰۰ بدهد.
5. در GitHub: Actions → deploy → Run workflow → environment=stage، image_tag=<sha>.

## ۵) چک‌لیستِ زندهٔ خط ایرانی (قبل از هر ترفیع به prod)

از یک اکانتِ تست، روی پروفایلِ stage:

- `https://www.google.com/generate_204` → ۲۰۴
- `https://gemini.google.com/app` → ۲۰۰
- `https://alkalimakersuite-pa.googleapis.com/` → ۳۰۲ (نه ۴۰۳)
- دانلود ۱MiB → حداقل ۵۰۰KB/s (اندازه‌گیری با رِنج، نه تک‌جریان)

## ۶) بازگشت

    # تگ قبلی را از Actions یا ghcr.io بردارید
    ssh <host> "cd /opt/ptunnel && ./deploy.sh stage <previous-sha> AraPersonal/PTunnel"

اگر کانفیگ مقصر بود: فایلِ `config/xray.json` با پسوندِ backup در همان مسیر است؛ برگردانید و
`docker restart ptunnel-xray-stage`.
