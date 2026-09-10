import MessageUI
import SwiftUI

/// レポートPDFを添付したメール作成画面（Apple の「メール」）。
///
/// 宛先・件名・本文を埋めた状態で開き、送信は操作者が行う。「メール」にアカウントがなく
/// `canSendMail` が false の端末では呼び出し側が共有シートへ切り替える。
struct MailComposeView: UIViewControllerRepresentable {
    struct Attachment {
        let data: Data
        let fileName: String
    }

    let content: ReportMailContent
    let attachment: Attachment
    let onFinish: () -> Void

    static var canSendMail: Bool {
        MFMailComposeViewController.canSendMail()
    }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let controller = MFMailComposeViewController()
        controller.mailComposeDelegate = context.coordinator
        controller.setToRecipients(ReportMailContent.recipients)
        controller.setSubject(content.subject)
        controller.setMessageBody(content.body, isHTML: false)
        controller.addAttachmentData(attachment.data, mimeType: "application/pdf", fileName: attachment.fileName)
        return controller
    }

    func updateUIViewController(_ uiViewController: MFMailComposeViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish)
    }

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        private let onFinish: () -> Void

        init(onFinish: @escaping () -> Void) {
            self.onFinish = onFinish
        }

        func mailComposeController(
            _ controller: MFMailComposeViewController,
            didFinishWith result: MFMailComposeResult,
            error: Error?
        ) {
            // 送信・下書き保存・取り消しのいずれでも画面を閉じるだけ。結果はアプリに残さない。
            onFinish()
        }
    }
}
