# Welcome to Claude Assistant for Eclipse OIE

Welcome to the Academy for Eclipse OIE, and to this course on the Claude Assistant.

The Claude Assistant is an extension for Open Integration Engine. It puts a Claude chat inside the OIE Administrator and the web administrator. You ask a question in plain language, such as *"Which channels have errors, and why?"* or *"What does the transformer in this channel do?"*, and Claude looks up the answer on your server itself: channels, messages, logs, scripts and code templates. It can also propose changes, like redeploying a channel or fixing a script, but nothing changes until you click **Run**.

## What you will learn

By the end of this course you will be able to:

- Install the extension and set it up with an Anthropic API key
- Open the assistant in the right place, so Claude gets the channel, message or script you mean as context
- Ask questions that get useful answers, and check Claude's conclusions
- Review and approve (or reject) the actions Claude proposes, and find them back in the audit log
- Explain what is masked before data leaves the server, and use *Review before sending*
- Assign the right permissions to users and roles
- Keep an eye on usage and costs, and solve common problems

## How the course works

Each section follows the [User Manual](https://github.com/matthydehoog/Claude-plugin-Eclipse-OIE/blob/main/docs/USER_MANUAL.md) and ends with a short quiz. The hands-on exercises use a **test channel**, so you can try every action safely. Please do not practise on a production server or with real patient data.

## Before you start

You will get the most out of the exercises if you have:

- An OIE server (4.6.0 or later) you may experiment on
- An Anthropic API key from the [Claude Console](https://console.anthropic.com), with credits or billing set up
- Access to the OIE Administrator, and optionally the web administrator with the Web Support extension

No test server yet? You can still follow the lessons and take the quizzes, and do the exercises later.

## A note on privacy and responsibility

The assistant masks patient data before anything is sent to Anthropic, but masking is a safety net, not anonymisation. Only send real patient data if your organization has a legal basis and a data processing agreement with Anthropic. And remember that Claude can be wrong: always check a proposed change before you click *Run*.

> This is a community-built plugin and course. It is not affiliated with, endorsed by or supported by Anthropic. Claude and Anthropic are trademarks of Anthropic, PBC.

Questions or found a bug? Open an issue on [GitHub](https://github.com/matthydehoog/Claude-plugin-Eclipse-OIE/issues).

Enjoy the course, and happy integrating!
