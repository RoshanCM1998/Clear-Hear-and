I want to build a simple Android App.
Let's name it clear-hear

Here idea is simple we want to build a hearing aid app. 

# UI
So create simple 2 text boxes 
1. Gain
2. Master Volume
Add a button to start and stop

# Functionality
- Show Start button initially
- On click of start button start recording audio on chuck of 100ms.
- Apply Gain on audio recording. Gain value will be on as 100 or 125 or 325 so first device it with 100. Now we will have 1 or 1.25 or 3.25 which is your multiple
- After that apply Master volume which will be in similar value as gain and you will get multiplayer you need to apply on audio.
- You need few things here 1. Permission to record audio, 2. Continue on background, 3. bluetooth or wirted audio innput output devices access
- When started it should work in backgroud
- When started you should show stop button
- Implement some parallel logic so when we processing and playing 100ms we should be able to record next 100ms in parallel so we should not lose any data.
- Use lib which are at root level of android so we don't get any delay while processing this things.
- Keep in mind while choise lib to do this that will next implment functionality to do audio processing where we will devide audio in 7 part and then apply equilizer for each band audio of human audible range. And Should be able to chose audio input and output devices.
- Write code to so that we will write proper logs and way to get that log file easily in either window or android so we can see what's going wrong.

# Other
1. Try to write this in less files(may be 1-2) as possible as it will be easy for me to understand. Create funtion with proper name.
2. Please create a doc where you will write your creation plean and reason for each step you are taking.
3. Create other doc on how to run it!
